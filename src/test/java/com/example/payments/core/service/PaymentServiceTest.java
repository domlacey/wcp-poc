package com.example.payments.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.core.domain.Money;
import com.example.payments.core.domain.Payment;
import com.example.payments.core.domain.PaymentAction;
import com.example.payments.core.domain.PaymentStatus;
import com.example.payments.core.provider.PaymentProvider;
import com.example.payments.core.provider.PaymentProviderRegistry;
import com.example.payments.core.provider.ProviderException;
import com.example.payments.core.provider.ProviderNotification;
import com.example.payments.core.provider.ProviderPaymentRequest;
import com.example.payments.core.provider.ProviderPaymentSession;
import com.example.payments.core.provider.ProviderPaymentUpdate;
import com.example.payments.core.provider.UnknownProviderException;
import com.example.payments.persistence.InMemoryPaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The core is tested with a fake provider only: it must not depend on any concrete provider. */
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final Money AMOUNT = new Money(1999, "CNY");

    private final FakeProvider provider = new FakeProvider();
    private final PaymentService service = new PaymentService(new PaymentProviderRegistry(List.of(provider)),
            new InMemoryPaymentRepository(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30));

    @Test
    void createsPendingPaymentWithProviderAction() {
        Payment payment = create("order-1");

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.id()).hasSize(32);
        assertThat(payment.nextAction()).isEqualTo(new PaymentAction(PaymentAction.Type.QR_CODE, "qr:" + payment.id()));
        assertThat(provider.created).singleElement().satisfies(r -> {
            assertThat(r.paymentReference()).isEqualTo(payment.id());
            assertThat(r.amount()).isEqualTo(AMOUNT);
            assertThat(r.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        });
        assertThat(service.getPayment(payment.id())).isSameAs(payment);
    }

    @Test
    void marksPaymentFailedWhenProviderRejectsIt() {
        provider.failCreate = new ProviderException("fake", "DECLINED", "nope");

        assertThatThrownBy(() -> create("order-1")).isSameAs(provider.failCreate);

        String id = provider.created.get(0).paymentReference();
        assertThat(service.getPayment(id).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(service.getPayment(id).failureReason()).isEqualTo("nope");
    }

    @Test
    void rejectsDuplicateMerchantOrder() {
        create("order-1");

        assertThatThrownBy(() -> create("order-1")).isInstanceOf(DuplicatePaymentException.class);
    }

    @Test
    void rejectsUnknownProvider() {
        assertThatThrownBy(() -> service.createPayment(new CreatePaymentCommand("o", AMOUNT, "d", "nope")))
                .isInstanceOf(UnknownProviderException.class);
    }

    @Test
    void notificationCompletesPaymentAndIsIdempotent() {
        Payment payment = create("order-1");
        provider.notificationUpdate = success(payment.id(), AMOUNT);

        service.handleNotification("fake", notification());
        service.handleNotification("fake", notification());

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.providerTransactionId()).isEqualTo("txn-1");
        assertThat(payment.paidAt()).isEqualTo(NOW);
        assertThat(payment.nextAction()).isNull();
    }

    @Test
    void rejectsNotificationWithDifferentAmount() {
        Payment payment = create("order-1");
        provider.notificationUpdate = success(payment.id(), new Money(1, "CNY"));

        assertThatThrownBy(() -> service.handleNotification("fake", notification()))
                .isInstanceOf(AmountMismatchException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void ignoresIrrelevantNotifications() {
        Payment payment = create("order-1");
        provider.notificationUpdate = null;

        service.handleNotification("fake", notification());

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void lateSuccessOverridesCancellation() {
        Payment payment = create("order-1");
        service.cancelPayment(payment.id());
        provider.notificationUpdate = success(payment.id(), AMOUNT);

        service.handleNotification("fake", notification());

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void refreshAppliesProviderStatus() {
        Payment payment = create("order-1");
        provider.queryUpdate = success(payment.id(), AMOUNT);

        assertThat(service.refreshPayment(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void refreshDoesNotCallProviderForFinalPayments() {
        Payment payment = create("order-1");
        service.cancelPayment(payment.id());
        provider.queryUpdate = null;

        assertThat(service.refreshPayment(payment.id()).status()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void cancelsPendingPaymentAtProvider() {
        Payment payment = create("order-1");

        service.cancelPayment(payment.id());

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(provider.cancelled).containsExactly(payment.id());
    }

    @Test
    void cannotCancelCompletedPayment() {
        Payment payment = create("order-1");
        provider.notificationUpdate = success(payment.id(), AMOUNT);
        service.handleNotification("fake", notification());

        assertThatThrownBy(() -> service.cancelPayment(payment.id()))
                .isInstanceOf(IllegalPaymentStateException.class);
        assertThat(provider.cancelled).isEmpty();
    }

    @Test
    void unknownPaymentIsNotFound() {
        assertThatThrownBy(() -> service.getPayment("missing")).isInstanceOf(PaymentNotFoundException.class);
    }

    private Payment create(String merchantOrderId) {
        return service.createPayment(new CreatePaymentCommand(merchantOrderId, AMOUNT, "Coffee", "fake"));
    }

    private static ProviderPaymentUpdate success(String id, Money amount) {
        return new ProviderPaymentUpdate(id, PaymentStatus.SUCCEEDED, "txn-1", amount, null, null);
    }

    private static ProviderNotification notification() {
        return new ProviderNotification(Map.of(), "{}");
    }

    static class FakeProvider implements PaymentProvider {
        final List<ProviderPaymentRequest> created = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        ProviderException failCreate;
        ProviderPaymentUpdate queryUpdate;
        ProviderPaymentUpdate notificationUpdate;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ProviderPaymentSession createPayment(ProviderPaymentRequest request) {
            created.add(request);
            if (failCreate != null) {
                throw failCreate;
            }
            return new ProviderPaymentSession(new PaymentAction(PaymentAction.Type.QR_CODE, "qr:" + request.paymentReference()));
        }

        @Override
        public ProviderPaymentUpdate queryPayment(String paymentReference) {
            if (queryUpdate == null) {
                throw new AssertionError("provider should not be queried");
            }
            return queryUpdate;
        }

        @Override
        public void cancelPayment(String paymentReference) {
            cancelled.add(paymentReference);
        }

        @Override
        public Optional<ProviderPaymentUpdate> parseNotification(ProviderNotification notification) {
            return Optional.ofNullable(notificationUpdate);
        }
    }
}

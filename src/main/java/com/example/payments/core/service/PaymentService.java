package com.example.payments.core.service;

import com.example.payments.core.domain.Payment;
import com.example.payments.core.domain.PaymentStatus;
import com.example.payments.core.provider.InvalidNotificationException;
import com.example.payments.core.provider.PaymentProvider;
import com.example.payments.core.provider.PaymentProviderRegistry;
import com.example.payments.core.provider.ProviderException;
import com.example.payments.core.provider.ProviderNotification;
import com.example.payments.core.provider.ProviderPaymentRequest;
import com.example.payments.core.provider.ProviderPaymentSession;
import com.example.payments.core.provider.ProviderPaymentUpdate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider-agnostic payment orchestration: owns the payment lifecycle and delegates provider work to
 * {@link PaymentProvider} adapters.
 */
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentProviderRegistry providers;
    private final PaymentRepository repository;
    private final Clock clock;
    private final Duration paymentExpiry;

    public PaymentService(PaymentProviderRegistry providers, PaymentRepository repository, Clock clock,
                          Duration paymentExpiry) {
        this.providers = providers;
        this.repository = repository;
        this.clock = clock;
        this.paymentExpiry = paymentExpiry;
    }

    public Payment createPayment(CreatePaymentCommand command) {
        PaymentProvider provider = providers.get(command.provider());
        Instant now = clock.instant();
        Payment payment = Payment.create(newPaymentId(), command.merchantOrderId(), provider.name(),
                command.amount(), command.description(), now);

        // Persist before calling the provider so an early notification can always find the payment.
        if (!repository.insert(payment)) {
            throw new DuplicatePaymentException(command.merchantOrderId());
        }

        try {
            ProviderPaymentSession session = provider.createPayment(new ProviderPaymentRequest(
                    payment.id(), payment.amount(), payment.description(), now.plus(paymentExpiry)));
            payment.attachNextAction(session.nextAction(), clock.instant());
            log.info("Created payment {} with provider {}", payment.id(), provider.name());
        } catch (ProviderException e) {
            payment.transitionTo(PaymentStatus.FAILED, null, null, e.getMessage(), clock.instant());
            log.warn("Provider {} rejected payment {}: {} {}", provider.name(), payment.id(), e.code(), e.getMessage());
            throw e;
        } finally {
            repository.save(payment);
        }
        return payment;
    }

    public Payment getPayment(String id) {
        return repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /** Pulls the latest status from the provider. Use when a notification is delayed or was missed. */
    public Payment refreshPayment(String id) {
        Payment payment = getPayment(id);
        if (payment.status().isFinal()) {
            return payment;
        }
        ProviderPaymentUpdate update = providers.get(payment.provider()).queryPayment(payment.id());
        applyUpdate(payment, update);
        return payment;
    }

    public Payment cancelPayment(String id) {
        Payment payment = getPayment(id);
        synchronized (payment) {
            if (payment.status() != PaymentStatus.PENDING) {
                throw new IllegalPaymentStateException(id, payment.status(), "cancel");
            }
            providers.get(payment.provider()).cancelPayment(payment.id());
            payment.transitionTo(PaymentStatus.CANCELLED, null, null, "Cancelled by merchant", clock.instant());
            repository.save(payment);
        }
        log.info("Cancelled payment {}", id);
        return payment;
    }

    /**
     * Handles an asynchronous provider notification. Safe to call repeatedly with the same notification:
     * providers retry until acknowledged, so duplicates are expected.
     */
    public void handleNotification(String providerName, ProviderNotification notification) {
        PaymentProvider provider = providers.get(providerName);
        Optional<ProviderPaymentUpdate> update = provider.parseNotification(notification);
        if (update.isEmpty()) {
            log.info("Ignoring {} notification that does not affect payment status", providerName);
            return;
        }
        Payment payment = getPayment(update.get().paymentReference());
        if (!payment.provider().equals(provider.name())) {
            throw new InvalidNotificationException(providerName,
                    "Payment " + payment.id() + " belongs to provider " + payment.provider());
        }
        applyUpdate(payment, update.get());
    }

    private void applyUpdate(Payment payment, ProviderPaymentUpdate update) {
        if (update.amount() != null && !update.amount().equals(payment.amount())) {
            log.error("Amount mismatch for payment {}: expected {}, provider reported {}",
                    payment.id(), payment.amount(), update.amount());
            throw new AmountMismatchException(payment.id(), payment.amount(), update.amount());
        }
        synchronized (payment) {
            PaymentStatus current = payment.status();
            if (update.status() == current) {
                return;
            }
            if (!current.canTransitionTo(update.status())) {
                log.warn("Ignoring update {} -> {} for payment {}", current, update.status(), payment.id());
                return;
            }
            payment.transitionTo(update.status(), update.providerTransactionId(), update.paidAt(),
                    update.reason(), clock.instant());
            repository.save(payment);
        }
        log.info("Payment {} is now {}", payment.id(), update.status());
    }

    private static String newPaymentId() {
        // 32 alphanumeric characters: fits common provider limits for merchant order references.
        return UUID.randomUUID().toString().replace("-", "");
    }
}

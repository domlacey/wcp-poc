package com.example.payments.core.provider;

import java.util.Optional;

/**
 * Port implemented by every payment provider adapter (WeChat Pay, Alipay, Stripe, ...).
 *
 * <p>The core payment service only talks to providers through this interface, so adding a provider means
 * adding an adapter, not changing the core. Implementations translate between these provider-neutral types
 * and the provider's own API, and own all provider-specific concerns: authentication, request signing,
 * response/notification verification, payload encryption and status mapping.
 *
 * <p>{@code paymentReference} is the platform payment id. Adapters send it to the provider as the
 * merchant-side order reference so payments can be looked up and correlated without extra state.
 */
public interface PaymentProvider {

    /** Stable identifier used in API requests and webhook URLs, e.g. {@code wechatpay}. */
    String name();

    /** Creates the payment at the provider and returns what the customer must do next. */
    ProviderPaymentSession createPayment(ProviderPaymentRequest request);

    /** Fetches the current status from the provider (used when a notification is late or missing). */
    ProviderPaymentUpdate queryPayment(String paymentReference);

    /** Cancels an unpaid payment at the provider. */
    void cancelPayment(String paymentReference);

    /**
     * Authenticates and decodes an asynchronous provider notification.
     *
     * @return the payment update, or empty if the notification is valid but not relevant to payments
     * @throws InvalidNotificationException if the notification cannot be authenticated or decoded
     */
    Optional<ProviderPaymentUpdate> parseNotification(ProviderNotification notification);
}

package com.example.payments.core.domain;

import java.util.Objects;

/**
 * What the client must do to let the customer complete the payment, expressed in provider-neutral terms.
 */
public record PaymentAction(Type type, String value) {

    public enum Type {
        /** Render {@code value} as a QR code for the customer to scan. */
        QR_CODE,
        /** Redirect the customer to the URL in {@code value}. */
        REDIRECT
    }

    public PaymentAction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}

package com.example.payments.api;

import com.example.payments.core.domain.Payment;
import com.example.payments.core.domain.PaymentAction;
import com.example.payments.core.domain.PaymentStatus;
import java.time.Instant;

public record PaymentResponse(String id,
                              String merchantOrderId,
                              String provider,
                              PaymentStatus status,
                              long amount,
                              String currency,
                              String description,
                              NextAction nextAction,
                              String providerTransactionId,
                              String failureReason,
                              Instant createdAt,
                              Instant updatedAt,
                              Instant paidAt) {

    public record NextAction(PaymentAction.Type type, String value) {
    }

    public static PaymentResponse from(Payment payment) {
        PaymentAction action = payment.nextAction();
        return new PaymentResponse(
                payment.id(),
                payment.merchantOrderId(),
                payment.provider(),
                payment.status(),
                payment.amount().amountMinor(),
                payment.amount().currency(),
                payment.description(),
                action == null ? null : new NextAction(action.type(), action.value()),
                payment.providerTransactionId(),
                payment.failureReason(),
                payment.createdAt(),
                payment.updatedAt(),
                payment.paidAt());
    }
}

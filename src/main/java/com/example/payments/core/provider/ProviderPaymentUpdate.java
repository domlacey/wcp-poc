package com.example.payments.core.provider;

import com.example.payments.core.domain.Money;
import com.example.payments.core.domain.PaymentStatus;
import java.time.Instant;

/**
 * Provider-reported state of a payment, already mapped to the platform's status model.
 *
 * @param amount the amount the provider reports for the payment, or {@code null} if unknown
 * @param reason human-readable reason for FAILED/CANCELLED, or {@code null}
 */
public record ProviderPaymentUpdate(String paymentReference,
                                    PaymentStatus status,
                                    String providerTransactionId,
                                    Money amount,
                                    Instant paidAt,
                                    String reason) {
}

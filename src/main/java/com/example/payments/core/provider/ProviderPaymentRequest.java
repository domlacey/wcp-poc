package com.example.payments.core.provider;

import com.example.payments.core.domain.Money;
import java.time.Instant;

public record ProviderPaymentRequest(String paymentReference, Money amount, String description, Instant expiresAt) {
}

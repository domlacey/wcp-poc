package com.example.payments.core.provider;

import com.example.payments.core.domain.PaymentAction;

public record ProviderPaymentSession(PaymentAction nextAction) {
}

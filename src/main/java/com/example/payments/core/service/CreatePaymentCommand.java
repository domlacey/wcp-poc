package com.example.payments.core.service;

import com.example.payments.core.domain.Money;

public record CreatePaymentCommand(String merchantOrderId, Money amount, String description, String provider) {
}

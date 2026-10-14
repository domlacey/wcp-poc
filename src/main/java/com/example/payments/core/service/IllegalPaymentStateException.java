package com.example.payments.core.service;

import com.example.payments.core.domain.PaymentStatus;

public class IllegalPaymentStateException extends RuntimeException {

    public IllegalPaymentStateException(String id, PaymentStatus status, String operation) {
        super("Cannot " + operation + " payment " + id + " in status " + status);
    }
}

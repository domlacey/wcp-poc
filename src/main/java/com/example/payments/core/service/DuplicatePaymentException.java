package com.example.payments.core.service;

public class DuplicatePaymentException extends RuntimeException {

    public DuplicatePaymentException(String merchantOrderId) {
        super("A payment already exists for merchant order " + merchantOrderId);
    }
}

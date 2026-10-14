package com.example.payments.core.service;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String id) {
        super("Payment not found: " + id);
    }
}

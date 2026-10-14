package com.example.payments.core.service;

import com.example.payments.core.domain.Money;

public class AmountMismatchException extends RuntimeException {

    public AmountMismatchException(String id, Money expected, Money reported) {
        super("Provider reported " + reported + " for payment " + id + " but expected " + expected);
    }
}

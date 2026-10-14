package com.example.payments.core.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * An amount in the currency's minor unit (e.g. fen for CNY, cents for USD).
 */
public record Money(long amountMinor, String currency) {

    public Money {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Objects.requireNonNull(currency, "currency");
        currency = currency.toUpperCase(Locale.ROOT);
    }
}

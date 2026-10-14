package com.example.payments.core.domain;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this != PENDING;
    }

    /**
     * SUCCEEDED is terminal. A FAILED or CANCELLED payment may still move to SUCCEEDED because the
     * provider is the source of truth for captured funds (e.g. a late success notification).
     */
    public boolean canTransitionTo(PaymentStatus next) {
        if (next == this) {
            return false;
        }
        return switch (this) {
            case PENDING -> true;
            case FAILED, CANCELLED -> next == SUCCEEDED;
            case SUCCEEDED -> false;
        };
    }
}

package com.example.payments.core.domain;

import java.time.Instant;
import java.util.Objects;

public class Payment {

    private final String id;
    private final String merchantOrderId;
    private final String provider;
    private final Money amount;
    private final String description;
    private final Instant createdAt;

    private PaymentStatus status;
    private PaymentAction nextAction;
    private String providerTransactionId;
    private String failureReason;
    private Instant paidAt;
    private Instant updatedAt;

    private Payment(String id, String merchantOrderId, String provider, Money amount, String description, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.merchantOrderId = Objects.requireNonNull(merchantOrderId, "merchantOrderId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.description = Objects.requireNonNull(description, "description");
        this.createdAt = now;
        this.updatedAt = now;
        this.status = PaymentStatus.PENDING;
    }

    public static Payment create(String id, String merchantOrderId, String provider, Money amount,
                                 String description, Instant now) {
        return new Payment(id, merchantOrderId, provider, amount, description, now);
    }

    public synchronized void attachNextAction(PaymentAction action, Instant now) {
        this.nextAction = action;
        this.updatedAt = now;
    }

    public synchronized void transitionTo(PaymentStatus target, String providerTransactionId, Instant paidAt,
                                          String reason, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Payment " + id + " cannot move from " + status + " to " + target);
        }
        this.status = target;
        if (providerTransactionId != null) {
            this.providerTransactionId = providerTransactionId;
        }
        if (target == PaymentStatus.SUCCEEDED) {
            this.paidAt = paidAt != null ? paidAt : now;
            this.failureReason = null;
            this.nextAction = null;
        } else if (target.isFinal()) {
            this.failureReason = reason;
            this.nextAction = null;
        }
        this.updatedAt = now;
    }

    public String id() { return id; }
    public String merchantOrderId() { return merchantOrderId; }
    public String provider() { return provider; }
    public Money amount() { return amount; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
    public synchronized PaymentStatus status() { return status; }
    public synchronized PaymentAction nextAction() { return nextAction; }
    public synchronized String providerTransactionId() { return providerTransactionId; }
    public synchronized String failureReason() { return failureReason; }
    public synchronized Instant paidAt() { return paidAt; }
    public synchronized Instant updatedAt() { return updatedAt; }
}

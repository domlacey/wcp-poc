package com.example.payments.core.service;

import com.example.payments.core.domain.Payment;
import java.util.Optional;

public interface PaymentRepository {

    /** Stores a new payment. Returns false if a payment for the same merchant order already exists. */
    boolean insert(Payment payment);

    void save(Payment payment);

    Optional<Payment> findById(String id);

    Optional<Payment> findByMerchantOrderId(String merchantOrderId);
}

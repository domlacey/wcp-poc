package com.example.payments.persistence;

import com.example.payments.core.domain.Payment;
import com.example.payments.core.service.PaymentRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-durable storage for the proof of concept. Replace with a database-backed implementation.
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<String, Payment> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByMerchantOrderId = new ConcurrentHashMap<>();

    @Override
    public boolean insert(Payment payment) {
        if (idByMerchantOrderId.putIfAbsent(payment.merchantOrderId(), payment.id()) != null) {
            return false;
        }
        byId.put(payment.id(), payment);
        return true;
    }

    @Override
    public void save(Payment payment) {
        byId.put(payment.id(), payment);
    }

    @Override
    public Optional<Payment> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Payment> findByMerchantOrderId(String merchantOrderId) {
        return Optional.ofNullable(idByMerchantOrderId.get(merchantOrderId)).flatMap(this::findById);
    }
}

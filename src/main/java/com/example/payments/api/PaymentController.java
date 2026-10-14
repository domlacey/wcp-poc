package com.example.payments.api;

import com.example.payments.core.domain.Money;
import com.example.payments.core.domain.Payment;
import com.example.payments.core.service.CreatePaymentCommand;
import com.example.payments.core.service.PaymentService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.createPayment(new CreatePaymentCommand(
                request.merchantOrderId(),
                new Money(request.amount(), request.currency()),
                request.description(),
                request.provider()));
        return ResponseEntity.created(URI.create("/api/payments/" + payment.id()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable String id) {
        return PaymentResponse.from(paymentService.getPayment(id));
    }

    @PostMapping("/{id}/refresh")
    public PaymentResponse refresh(@PathVariable String id) {
        return PaymentResponse.from(paymentService.refreshPayment(id));
    }

    @PostMapping("/{id}/cancel")
    public PaymentResponse cancel(@PathVariable String id) {
        return PaymentResponse.from(paymentService.cancelPayment(id));
    }
}

package com.example.payments.api;

import com.example.payments.core.provider.ProviderNotification;
import com.example.payments.core.service.PaymentService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single entry point for asynchronous provider notifications. The raw body is passed through untouched
 * because providers sign the exact bytes they send.
 *
 * <p>Returns 204 once the notification is processed; any non-2xx response tells the provider to retry.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final PaymentService paymentService;

    public WebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider,
                                        @RequestHeader HttpHeaders headers,
                                        @RequestBody(required = false) byte[] body) {
        String payload = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        paymentService.handleNotification(provider, new ProviderNotification(headers.toSingleValueMap(), payload));
        return ResponseEntity.noContent().build();
    }
}

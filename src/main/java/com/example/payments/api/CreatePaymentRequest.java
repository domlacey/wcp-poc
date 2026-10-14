package com.example.payments.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param amount amount in the currency's minor unit (fen for CNY)
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 64) String merchantOrderId,
        @NotNull @Positive Long amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency,
        @NotBlank @Size(max = 127) String description,
        @NotBlank String provider) {
}

package com.example.payments.api;

import com.example.payments.core.provider.InvalidNotificationException;
import com.example.payments.core.provider.ProviderException;
import com.example.payments.core.provider.UnknownProviderException;
import com.example.payments.core.service.AmountMismatchException;
import com.example.payments.core.service.DuplicatePaymentException;
import com.example.payments.core.service.IllegalPaymentStateException;
import com.example.payments.core.service.PaymentNotFoundException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(PaymentNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(DuplicatePaymentException.class)
    ResponseEntity<ErrorResponse> duplicate(DuplicatePaymentException e) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_PAYMENT", e.getMessage());
    }

    @ExceptionHandler(IllegalPaymentStateException.class)
    ResponseEntity<ErrorResponse> illegalState(IllegalPaymentStateException e) {
        return error(HttpStatus.CONFLICT, "ILLEGAL_PAYMENT_STATE", e.getMessage());
    }

    @ExceptionHandler(UnknownProviderException.class)
    ResponseEntity<ErrorResponse> unknownProvider(UnknownProviderException e) {
        return error(HttpStatus.BAD_REQUEST, "UNKNOWN_PROVIDER", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed");
    }

    @ExceptionHandler(InvalidNotificationException.class)
    ResponseEntity<ErrorResponse> invalidNotification(InvalidNotificationException e) {
        log.warn("Rejected {} notification: {}", e.provider(), e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
    }

    @ExceptionHandler(AmountMismatchException.class)
    ResponseEntity<ErrorResponse> amountMismatch(AmountMismatchException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_MISMATCH", e.getMessage());
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ErrorResponse> provider(ProviderException e) {
        return error(HttpStatus.BAD_GATEWAY, e.code(), e.provider() + ": " + e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}

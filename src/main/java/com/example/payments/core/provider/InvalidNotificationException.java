package com.example.payments.core.provider;

public class InvalidNotificationException extends ProviderException {

    public InvalidNotificationException(String provider, String message) {
        this(provider, message, null);
    }

    public InvalidNotificationException(String provider, String message, Throwable cause) {
        super(provider, "INVALID_NOTIFICATION", message, cause);
    }
}

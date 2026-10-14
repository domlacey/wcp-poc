package com.example.payments.core.provider;

/**
 * A provider call failed or returned something the platform cannot use.
 */
public class ProviderException extends RuntimeException {

    private final String provider;
    private final String code;

    public ProviderException(String provider, String code, String message) {
        this(provider, code, message, null);
    }

    public ProviderException(String provider, String code, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.code = code;
    }

    public String provider() {
        return provider;
    }

    public String code() {
        return code;
    }
}

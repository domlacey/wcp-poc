package com.example.payments.core.provider;

import java.util.Set;

public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException(String name, Set<String> available) {
        super("Unknown payment provider '" + name + "'. Available: " + available);
    }
}

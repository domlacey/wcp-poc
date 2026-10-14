package com.example.payments.core.provider;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A raw, unverified notification as received over HTTP. The body must be the exact received payload
 * because providers sign it.
 */
public record ProviderNotification(Map<String, String> headers, String body) {

    public ProviderNotification {
        TreeMap<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        headers = Collections.unmodifiableMap(caseInsensitive);
        body = body == null ? "" : body;
    }

    public String header(String name) {
        return headers.get(name);
    }
}

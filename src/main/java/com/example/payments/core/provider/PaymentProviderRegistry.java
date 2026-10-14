package com.example.payments.core.provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class PaymentProviderRegistry {

    private final Map<String, PaymentProvider> providers = new LinkedHashMap<>();

    public PaymentProviderRegistry(Collection<? extends PaymentProvider> providers) {
        for (PaymentProvider provider : providers) {
            if (this.providers.putIfAbsent(provider.name(), provider) != null) {
                throw new IllegalStateException("Duplicate payment provider: " + provider.name());
            }
        }
    }

    public PaymentProvider get(String name) {
        PaymentProvider provider = providers.get(name);
        if (provider == null) {
            throw new UnknownProviderException(name, providers.keySet());
        }
        return provider;
    }

    public Set<String> names() {
        return Set.copyOf(providers.keySet());
    }
}

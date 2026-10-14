package com.example.payments.config;

import com.example.payments.core.provider.PaymentProvider;
import com.example.payments.core.provider.PaymentProviderRegistry;
import com.example.payments.core.service.PaymentRepository;
import com.example.payments.core.service.PaymentService;
import com.example.payments.persistence.InMemoryPaymentRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free core. Provider adapters contribute {@link PaymentProvider} beans;
 * the core picks them up without knowing which providers exist.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentsConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentProviderRegistry paymentProviderRegistry(ObjectProvider<PaymentProvider> providers) {
        return new PaymentProviderRegistry(providers.orderedStream().toList());
    }

    @Bean
    PaymentRepository paymentRepository() {
        return new InMemoryPaymentRepository();
    }

    @Bean
    PaymentService paymentService(PaymentProviderRegistry registry,
                                  PaymentRepository repository,
                                  Clock clock,
                                  @Value("${payments.expiry:PT30M}") Duration expiry) {
        return new PaymentService(registry, repository, clock, expiry);
    }
}

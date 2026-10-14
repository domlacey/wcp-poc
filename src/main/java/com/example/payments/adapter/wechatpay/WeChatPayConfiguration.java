package com.example.payments.adapter.wechatpay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the WeChat Pay adapter as a {@code PaymentProvider} when {@code wechatpay.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wechatpay", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WeChatPayProperties.class)
public class WeChatPayConfiguration {

    @Bean
    WeChatPayAdapter weChatPayAdapter(WeChatPayProperties props, Clock clock) {
        String mchId = required(props.mchId(), "mch-id");
        WeChatPaySigner signer = new WeChatPaySigner(
                mchId,
                required(props.merchantSerialNumber(), "merchant-serial-number"),
                PemKeys.privateKey(pem(props.merchantPrivateKey(), props.merchantPrivateKeyPath(), "merchant-private-key")),
                clock);
        WeChatPayVerifier verifier = new WeChatPayVerifier(
                required(props.platformPublicKeyId(), "platform-public-key-id"),
                PemKeys.publicKey(pem(props.platformPublicKey(), props.platformPublicKeyPath(), "platform-public-key")),
                clock);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        WeChatPayClient client = new WeChatPayClient(URI.create(props.baseUrl()), httpClient, signer, verifier,
                WeChatPayJson.mapper(), props.readTimeout());
        return new WeChatPayAdapter(
                client,
                verifier,
                new AesGcmDecryptor(required(props.apiV3Key(), "api-v3-key")),
                required(props.appId(), "app-id"),
                mchId,
                required(props.notifyUrl(), "notify-url"));
    }

    private static String pem(String inline, String path, String name) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String file = required(path, name + "-path");
        try {
            return Files.readString(Path.of(file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read wechatpay." + name + "-path: " + file, e);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property wechatpay." + name);
        }
        return value;
    }
}

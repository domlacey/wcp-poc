package com.example.payments.adapter.wechatpay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Verifies signatures WeChat Pay puts on API responses and notifications, using the WeChat Pay public key
 * (or platform certificate) identified by {@code Wechatpay-Serial}.
 *
 * <pre>message = Wechatpay-Timestamp\n + Wechatpay-Nonce\n + body\n</pre>
 */
public final class WeChatPayVerifier {

    public static final String HEADER_SERIAL = "Wechatpay-Serial";
    public static final String HEADER_TIMESTAMP = "Wechatpay-Timestamp";
    public static final String HEADER_NONCE = "Wechatpay-Nonce";
    public static final String HEADER_SIGNATURE = "Wechatpay-Signature";

    /** Reject stale messages to limit replay. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final String publicKeyId;
    private final PublicKey publicKey;
    private final Clock clock;

    public WeChatPayVerifier(String publicKeyId, PublicKey publicKey, Clock clock) {
        this.publicKeyId = publicKeyId;
        this.publicKey = publicKey;
        this.clock = clock;
    }

    public void verify(String serial, String timestamp, String nonce, String signature, String body) {
        if (serial == null || timestamp == null || nonce == null || signature == null) {
            throw new WeChatPaySecurityException("Missing WeChat Pay signature headers");
        }
        if (!publicKeyId.equals(serial)) {
            throw new WeChatPaySecurityException("Unknown WeChat Pay public key serial: " + serial);
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new WeChatPaySecurityException("Invalid Wechatpay-Timestamp: " + timestamp);
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(epochSeconds), clock.instant()).abs();
        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new WeChatPaySecurityException("Wechatpay-Timestamp outside allowed window: " + timestamp);
        }
        String message = timestamp + "\n" + nonce + "\n" + (body == null ? "" : body) + "\n";
        boolean valid;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            valid = verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new WeChatPaySecurityException("Unable to verify WeChat Pay signature", e);
        }
        if (!valid) {
            throw new WeChatPaySecurityException("Invalid WeChat Pay signature");
        }
    }
}

package com.example.payments.adapter.wechatpay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;

/**
 * Signs outgoing API v3 requests with the merchant API private key (SHA256withRSA).
 *
 * <pre>
 * message       = METHOD\n + URL(path?query)\n + timestamp\n + nonce\n + body\n
 * Authorization = WECHATPAY2-SHA256-RSA2048 mchid="..",nonce_str="..",timestamp="..",serial_no="..",signature=".."
 * </pre>
 */
public final class WeChatPaySigner {

    public static final String AUTH_SCHEMA = "WECHATPAY2-SHA256-RSA2048";

    private static final String NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String mchId;
    private final String merchantSerialNumber;
    private final PrivateKey privateKey;
    private final Clock clock;

    public WeChatPaySigner(String mchId, String merchantSerialNumber, PrivateKey privateKey, Clock clock) {
        this.mchId = mchId;
        this.merchantSerialNumber = merchantSerialNumber;
        this.privateKey = privateKey;
        this.clock = clock;
    }

    /**
     * @param canonicalUrl absolute path plus query string, exactly as sent, e.g. {@code /v3/pay/transactions/native}
     * @param body         request body, or an empty string for GET
     */
    public String authorization(String method, String canonicalUrl, String body) {
        String nonce = nonce();
        long timestamp = clock.instant().getEpochSecond();
        String message = method + "\n" + canonicalUrl + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        return AUTH_SCHEMA + " mchid=\"" + mchId + "\",nonce_str=\"" + nonce + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + merchantSerialNumber + "\",signature=\"" + sign(message) + "\"";
    }

    public String sign(String message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign WeChat Pay request", e);
        }
    }

    private static String nonce() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(NONCE_CHARS.charAt(RANDOM.nextInt(NONCE_CHARS.length())));
        }
        return sb.toString();
    }
}

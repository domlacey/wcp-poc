package com.example.payments.adapter.wechatpay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Plays the WeChat Pay side in tests: holds a fake merchant key pair and a fake WeChat Pay key pair, signs
 * responses/notifications the way WeChat Pay does and encrypts notification resources with the APIv3 key.
 */
public final class WeChatPayTestSupport {

    public static final String MCH_ID = "1900000001";
    public static final String APP_ID = "wxd678efh567hg6787";
    public static final String MERCHANT_SERIAL = "5157F09EFDC096DE15EBE81A47057A7232F1B8E1";
    public static final String PLATFORM_KEY_ID = "PUB_KEY_ID_0000000000000000000000000001";
    public static final String API_V3_KEY = "0123456789abcdefghijklmnopqrstuv";
    public static final String NOTIFY_URL = "https://pay.example.com/api/webhooks/wechatpay";

    private static final Pattern AUTH_PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    public final KeyPair merchantKeys = generateRsaKeyPair();
    public final KeyPair platformKeys = generateRsaKeyPair();

    public String merchantPrivateKeyPem() {
        return pem("PRIVATE KEY", merchantKeys.getPrivate().getEncoded());
    }

    public String platformPublicKeyPem() {
        return pem("PUBLIC KEY", platformKeys.getPublic().getEncoded());
    }

    /** Headers WeChat Pay attaches to a response or notification with the given body. */
    public Map<String, String> signedHeaders(String body) {
        return signedHeaders(body, Instant.now(), PLATFORM_KEY_ID);
    }

    public Map<String, String> signedHeaders(String body, Instant timestamp, String serial) {
        String ts = Long.toString(timestamp.getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> headers = new HashMap<>();
        headers.put(WeChatPayVerifier.HEADER_SERIAL, serial);
        headers.put(WeChatPayVerifier.HEADER_TIMESTAMP, ts);
        headers.put(WeChatPayVerifier.HEADER_NONCE, nonce);
        headers.put(WeChatPayVerifier.HEADER_SIGNATURE, sign(ts + "\n" + nonce + "\n" + body + "\n"));
        return headers;
    }

    public ResponseDefinitionBuilder signedResponse(int status, String body) {
        ResponseDefinitionBuilder response = aResponse().withStatus(status);
        if (!body.isEmpty()) {
            response.withHeader("Content-Type", "application/json").withBody(body);
        }
        signedHeaders(body).forEach(response::withHeader);
        return response;
    }

    public static String transactionJson(String outTradeNo, String tradeState, long total) {
        return """
                {"appid":"%s","mchid":"%s","out_trade_no":"%s","transaction_id":"4200000985202310141234567890",\
                "trade_type":"NATIVE","trade_state":"%s","trade_state_desc":"desc","bank_type":"OTHERS",\
                "success_time":"2026-10-14T19:30:00+08:00","payer":{"openid":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"},\
                "amount":{"total":%d,"payer_total":%d,"currency":"CNY","payer_currency":"CNY"}}"""
                .formatted(APP_ID, MCH_ID, outTradeNo, tradeState, total, total);
    }

    /** A TRANSACTION.SUCCESS notification envelope whose resource is encrypted with the given APIv3 key. */
    public static String notificationJson(String resourcePlaintext, String apiV3Key, String originalType) {
        String nonce = "fdasflkja484";
        String associatedData = originalType;
        String ciphertext = encrypt(resourcePlaintext, apiV3Key, nonce, associatedData);
        return """
                {"id":"EV-2018022511223320873","create_time":"2026-10-14T19:30:01+08:00",\
                "resource_type":"encrypt-resource","event_type":"TRANSACTION.SUCCESS","summary":"支付成功",\
                "resource":{"original_type":"%s","algorithm":"AEAD_AES_256_GCM","ciphertext":"%s",\
                "associated_data":"%s","nonce":"%s"}}"""
                .formatted(originalType, ciphertext, associatedData, nonce);
    }

    public static String paymentSuccessNotification(String outTradeNo, long total) {
        return notificationJson(transactionJson(outTradeNo, "SUCCESS", total), API_V3_KEY, "transaction");
    }

    public static String encrypt(String plaintext, String key, String nonce, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses a merchant Authorization header into its parameters (mchid, nonce_str, timestamp, ...). */
    public static Map<String, String> parseAuthorization(String header) {
        if (!header.startsWith(WeChatPaySigner.AUTH_SCHEMA + " ")) {
            throw new IllegalArgumentException("Unexpected schema: " + header);
        }
        Map<String, String> params = new HashMap<>();
        Matcher m = AUTH_PARAM.matcher(header);
        while (m.find()) {
            params.put(m.group(1), m.group(2));
        }
        return params;
    }

    /** Verifies a merchant Authorization header the way WeChat Pay would. */
    public static boolean isValidMerchantSignature(String header, String method, String url, String body,
                                                   PublicKey merchantPublicKey) {
        Map<String, String> p = parseAuthorization(header);
        String message = method + "\n" + url + "\n" + p.get("timestamp") + "\n" + p.get("nonce_str") + "\n" + body + "\n";
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(merchantPublicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(p.get("signature")));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sign(String message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(platformKeys.getPrivate());
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}

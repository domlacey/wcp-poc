package com.example.payments.adapter.wechatpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeChatPaySignerTest {

    private static final WeChatPayTestSupport WECHAT = new WeChatPayTestSupport();
    private static final Instant NOW = Instant.parse("2026-10-14T11:00:00Z");

    private final WeChatPaySigner signer = new WeChatPaySigner(WeChatPayTestSupport.MCH_ID,
            WeChatPayTestSupport.MERCHANT_SERIAL, PemKeys.privateKey(WECHAT.merchantPrivateKeyPem()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void buildsAuthorizationHeaderWithAllRequiredParameters() {
        String header = signer.authorization("POST", "/v3/pay/transactions/native", "{\"a\":1}");

        assertThat(header).startsWith("WECHATPAY2-SHA256-RSA2048 ");
        Map<String, String> params = WeChatPayTestSupport.parseAuthorization(header);
        assertThat(params).containsEntry("mchid", WeChatPayTestSupport.MCH_ID)
                .containsEntry("serial_no", WeChatPayTestSupport.MERCHANT_SERIAL)
                .containsEntry("timestamp", String.valueOf(NOW.getEpochSecond()))
                .containsKeys("nonce_str", "signature");
        assertThat(params.get("nonce_str")).hasSize(32).matches("[A-Z0-9]+");
    }

    @Test
    void signatureCoversMethodUrlTimestampNonceAndBody() {
        String body = "{\"mchid\":\"1900000001\"}";
        String header = signer.authorization("POST", "/v3/pay/transactions/out-trade-no/abc/close", body);
        var merchantPublicKey = WECHAT.merchantKeys.getPublic();

        assertThat(WeChatPayTestSupport.isValidMerchantSignature(header, "POST",
                "/v3/pay/transactions/out-trade-no/abc/close", body, merchantPublicKey)).isTrue();
        assertThat(WeChatPayTestSupport.isValidMerchantSignature(header, "POST",
                "/v3/pay/transactions/out-trade-no/abc/close", body + " ", merchantPublicKey)).isFalse();
        assertThat(WeChatPayTestSupport.isValidMerchantSignature(header, "GET",
                "/v3/pay/transactions/out-trade-no/abc/close", body, merchantPublicKey)).isFalse();
    }

    @Test
    void usesFreshNoncePerRequest() {
        String first = WeChatPayTestSupport.parseAuthorization(signer.authorization("GET", "/v3/x", "")).get("nonce_str");
        String second = WeChatPayTestSupport.parseAuthorization(signer.authorization("GET", "/v3/x", "")).get("nonce_str");

        assertThat(first).isNotEqualTo(second);
    }
}

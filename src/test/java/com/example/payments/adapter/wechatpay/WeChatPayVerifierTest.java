package com.example.payments.adapter.wechatpay;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeChatPayVerifierTest {

    private static final WeChatPayTestSupport WECHAT = new WeChatPayTestSupport();
    private static final String BODY = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=abc\"}";

    private final WeChatPayVerifier verifier = new WeChatPayVerifier(WeChatPayTestSupport.PLATFORM_KEY_ID,
            PemKeys.publicKey(WECHAT.platformPublicKeyPem()), Clock.systemUTC());

    @Test
    void acceptsValidSignature() {
        assertThatCode(() -> verify(WECHAT.signedHeaders(BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTamperedBody() {
        Map<String, String> headers = WECHAT.signedHeaders(BODY);

        assertThatThrownBy(() -> verify(headers, BODY.replace("abc", "evil")))
                .isInstanceOf(WeChatPaySecurityException.class)
                .hasMessageContaining("Invalid WeChat Pay signature");
    }

    @Test
    void rejectsUnknownSerial() {
        Map<String, String> headers = WECHAT.signedHeaders(BODY, Instant.now(), "PUB_KEY_ID_OTHER");

        assertThatThrownBy(() -> verify(headers, BODY))
                .isInstanceOf(WeChatPaySecurityException.class)
                .hasMessageContaining("Unknown WeChat Pay public key serial");
    }

    @Test
    void rejectsStaleTimestamp() {
        Map<String, String> headers = WECHAT.signedHeaders(BODY, Instant.now().minus(Duration.ofMinutes(6)),
                WeChatPayTestSupport.PLATFORM_KEY_ID);

        assertThatThrownBy(() -> verify(headers, BODY))
                .isInstanceOf(WeChatPaySecurityException.class)
                .hasMessageContaining("outside allowed window");
    }

    @Test
    void rejectsSignatureFromAnotherKey() {
        WeChatPayTestSupport impostor = new WeChatPayTestSupport();

        assertThatThrownBy(() -> verify(impostor.signedHeaders(BODY), BODY))
                .isInstanceOf(WeChatPaySecurityException.class);
    }

    @Test
    void rejectsMissingHeaders() {
        assertThatThrownBy(() -> verifier.verify(null, null, null, null, BODY))
                .isInstanceOf(WeChatPaySecurityException.class)
                .hasMessageContaining("Missing");
    }

    private void verify(Map<String, String> headers, String body) {
        verifier.verify(headers.get(WeChatPayVerifier.HEADER_SERIAL), headers.get(WeChatPayVerifier.HEADER_TIMESTAMP),
                headers.get(WeChatPayVerifier.HEADER_NONCE), headers.get(WeChatPayVerifier.HEADER_SIGNATURE), body);
    }
}

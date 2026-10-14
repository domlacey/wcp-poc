package com.example.payments.adapter.wechatpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AesGcmDecryptorTest {

    private static final String KEY = WeChatPayTestSupport.API_V3_KEY;
    private final AesGcmDecryptor decryptor = new AesGcmDecryptor(KEY);

    @Test
    void decryptsResourceEncryptedWithApiV3Key() {
        String ciphertext = WeChatPayTestSupport.encrypt("{\"trade_state\":\"SUCCESS\"}", KEY, "nonce1234567", "transaction");

        assertThat(decryptor.decrypt("transaction", "nonce1234567", ciphertext)).isEqualTo("{\"trade_state\":\"SUCCESS\"}");
    }

    @Test
    void failsWhenAssociatedDataDoesNotMatch() {
        String ciphertext = WeChatPayTestSupport.encrypt("secret", KEY, "nonce1234567", "transaction");

        assertThatThrownBy(() -> decryptor.decrypt("refund", "nonce1234567", ciphertext))
                .isInstanceOf(WeChatPaySecurityException.class);
    }

    @Test
    void failsWithWrongKey() {
        String ciphertext = WeChatPayTestSupport.encrypt("secret", "vutsrqponmlkjihgfedcba9876543210", "nonce1234567", "transaction");

        assertThatThrownBy(() -> decryptor.decrypt("transaction", "nonce1234567", ciphertext))
                .isInstanceOf(WeChatPaySecurityException.class);
    }

    @Test
    void requiresThirtyTwoByteKey() {
        assertThatThrownBy(() -> new AesGcmDecryptor("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

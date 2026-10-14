package com.example.payments.adapter.wechatpay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts {@code AEAD_AES_256_GCM} resources in WeChat Pay notifications with the merchant APIv3 key.
 * The ciphertext is Base64 and carries the 16-byte authentication tag at the end.
 */
public final class AesGcmDecryptor {

    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;

    public AesGcmDecryptor(String apiV3Key) {
        byte[] keyBytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("WeChat Pay APIv3 key must be 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String decrypt(String associatedData, String nonce, String ciphertextBase64) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new WeChatPaySecurityException("Unable to decrypt WeChat Pay resource", e);
        }
    }
}

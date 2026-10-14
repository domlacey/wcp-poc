package com.example.payments.adapter.wechatpay;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Loads the RSA keys WeChat Pay issues in PEM format. */
public final class PemKeys {

    private PemKeys() {
    }

    /** Merchant API private key ({@code apiclient_key.pem}, PKCS#8). */
    public static PrivateKey privateKey(String pem) {
        try {
            byte[] der = decode(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid PKCS#8 RSA private key", e);
        }
    }

    /**
     * WeChat Pay public key ({@code pub_key.pem}) or, for merchants still on platform certificates, the
     * platform certificate ({@code -----BEGIN CERTIFICATE-----}).
     */
    public static PublicKey publicKey(String pem) {
        try {
            if (pem.contains("BEGIN CERTIFICATE")) {
                return CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
                        .getPublicKey();
            }
            byte[] der = decode(pem, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid RSA public key or certificate", e);
        }
    }

    private static byte[] decode(String pem, String type) {
        String base64 = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}

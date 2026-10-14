package com.example.payments.adapter.wechatpay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * WeChat Pay merchant configuration (prefix {@code wechatpay}). Keys can be given inline as PEM text or as
 * file paths; inline values win.
 *
 * @param mchId                  merchant id (商户号)
 * @param appId                  AppID bound to the merchant (Official Account / Mini Program / App)
 * @param merchantSerialNumber   serial number of the merchant API certificate (商户API证书序列号)
 * @param apiV3Key               32-byte APIv3 key used to decrypt notifications
 * @param platformPublicKeyId    WeChat Pay public key id ({@code PUB_KEY_ID_...}) or platform certificate serial
 * @param notifyUrl              public HTTPS URL WeChat Pay posts notifications to, e.g.
 *                               {@code https://pay.example.com/api/webhooks/wechatpay}
 */
@ConfigurationProperties(prefix = "wechatpay")
public record WeChatPayProperties(
        boolean enabled,
        @DefaultValue("https://api.mch.weixin.qq.com") String baseUrl,
        String mchId,
        String appId,
        String merchantSerialNumber,
        String merchantPrivateKey,
        String merchantPrivateKeyPath,
        String apiV3Key,
        String platformPublicKeyId,
        String platformPublicKey,
        String platformPublicKeyPath,
        String notifyUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {
}

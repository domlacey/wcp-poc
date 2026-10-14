package com.example.payments.adapter.wechatpay;

import com.example.payments.core.provider.ProviderException;

/** A WeChat Pay signature could not be verified or an encrypted payload could not be decrypted. */
public class WeChatPaySecurityException extends ProviderException {

    public WeChatPaySecurityException(String message) {
        this(message, null);
    }

    public WeChatPaySecurityException(String message, Throwable cause) {
        super(WeChatPayAdapter.PROVIDER_NAME, "SECURITY_ERROR", message, cause);
    }
}

package com.example.payments.adapter.wechatpay;

import com.example.payments.core.provider.ProviderException;

/** WeChat Pay returned a non-2xx response, e.g. {@code 400 PARAM_ERROR} or {@code 404 ORDER_NOT_EXIST}. */
public class WeChatPayApiException extends ProviderException {

    private final int httpStatus;

    public WeChatPayApiException(int httpStatus, String code, String message) {
        super(WeChatPayAdapter.PROVIDER_NAME, code, message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}

package com.example.payments.adapter.wechatpay;

import com.example.payments.core.domain.PaymentStatus;

/** Maps WeChat Pay {@code trade_state} values to the platform's payment status. */
final class WeChatPayStatusMapper {

    private WeChatPayStatusMapper() {
    }

    static PaymentStatus toPaymentStatus(String tradeState) {
        if (tradeState == null) {
            return PaymentStatus.PENDING;
        }
        return switch (tradeState) {
            // REFUND means the payment succeeded and a refund was later requested; refunds are out of scope.
            case "SUCCESS", "REFUND" -> PaymentStatus.SUCCEEDED;
            case "NOTPAY", "USERPAYING" -> PaymentStatus.PENDING;
            case "CLOSED", "REVOKED" -> PaymentStatus.CANCELLED;
            case "PAYERROR" -> PaymentStatus.FAILED;
            default -> PaymentStatus.PENDING;
        };
    }
}

package com.example.payments.adapter.wechatpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.core.domain.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeChatPayStatusMapperTest {

    @ParameterizedTest
    @CsvSource({
            "SUCCESS, SUCCEEDED",
            "REFUND, SUCCEEDED",
            "NOTPAY, PENDING",
            "USERPAYING, PENDING",
            "CLOSED, CANCELLED",
            "REVOKED, CANCELLED",
            "PAYERROR, FAILED",
            "SOMETHING_NEW, PENDING"
    })
    void mapsTradeStateToPaymentStatus(String tradeState, PaymentStatus expected) {
        assertThat(WeChatPayStatusMapper.toPaymentStatus(tradeState)).isEqualTo(expected);
    }
}

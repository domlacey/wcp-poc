package com.example.payments.adapter.wechatpay;

/**
 * WeChat Pay API v3 JSON payloads used by this adapter. Field names are mapped to snake_case by
 * {@link WeChatPayJson}. Only the fields the adapter needs are modelled; unknown fields are ignored.
 */
final class WeChatPayModels {

    private WeChatPayModels() {
    }

    /** POST /v3/pay/transactions/native */
    record NativePrepayRequest(String appid,
                               String mchid,
                               String description,
                               String outTradeNo,
                               String timeExpire,
                               String notifyUrl,
                               Amount amount) {
    }

    record Amount(Integer total, String currency) {
    }

    record NativePrepayResponse(String codeUrl) {
    }

    /** POST /v3/pay/transactions/out-trade-no/{out_trade_no}/close */
    record CloseRequest(String mchid) {
    }

    /** Query response and decrypted payment notification resource. */
    record Transaction(String appid,
                       String mchid,
                       String outTradeNo,
                       String transactionId,
                       String tradeType,
                       String tradeState,
                       String tradeStateDesc,
                       String successTime,
                       TransactionAmount amount) {
    }

    record TransactionAmount(Integer total, Integer payerTotal, String currency, String payerCurrency) {
    }

    /** Envelope of an asynchronous notification sent to {@code notify_url}. */
    record Notification(String id,
                        String createTime,
                        String eventType,
                        String resourceType,
                        String summary,
                        EncryptedResource resource) {
    }

    record EncryptedResource(String algorithm,
                             String ciphertext,
                             String associatedData,
                             String originalType,
                             String nonce) {
    }

    record ErrorBody(String code, String message) {
    }
}

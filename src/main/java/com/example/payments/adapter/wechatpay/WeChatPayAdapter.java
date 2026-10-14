package com.example.payments.adapter.wechatpay;

import com.example.payments.adapter.wechatpay.WeChatPayModels.Amount;
import com.example.payments.adapter.wechatpay.WeChatPayModels.CloseRequest;
import com.example.payments.adapter.wechatpay.WeChatPayModels.EncryptedResource;
import com.example.payments.adapter.wechatpay.WeChatPayModels.NativePrepayRequest;
import com.example.payments.adapter.wechatpay.WeChatPayModels.NativePrepayResponse;
import com.example.payments.adapter.wechatpay.WeChatPayModels.Notification;
import com.example.payments.adapter.wechatpay.WeChatPayModels.Transaction;
import com.example.payments.core.domain.Money;
import com.example.payments.core.domain.PaymentAction;
import com.example.payments.core.domain.PaymentStatus;
import com.example.payments.core.provider.InvalidNotificationException;
import com.example.payments.core.provider.PaymentProvider;
import com.example.payments.core.provider.ProviderException;
import com.example.payments.core.provider.ProviderNotification;
import com.example.payments.core.provider.ProviderPaymentRequest;
import com.example.payments.core.provider.ProviderPaymentSession;
import com.example.payments.core.provider.ProviderPaymentUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * {@link PaymentProvider} for WeChat Pay API v3 using Native payments: WeChat Pay returns a {@code code_url}
 * that the merchant shows as a QR code and the customer scans in WeChat.
 *
 * <p>Uses the merchant's {@code out_trade_no} (= platform payment id) for every follow-up call, so no
 * WeChat Pay identifiers need to be stored by the core.
 */
public class WeChatPayAdapter implements PaymentProvider {

    public static final String PROVIDER_NAME = "wechatpay";

    static final String NATIVE_PREPAY_PATH = "/v3/pay/transactions/native";
    static final String OUT_TRADE_NO_PATH = "/v3/pay/transactions/out-trade-no/";

    private static final String CURRENCY = "CNY";
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final WeChatPayClient client;
    private final WeChatPayVerifier verifier;
    private final AesGcmDecryptor decryptor;
    private final ObjectMapper mapper;
    private final String appId;
    private final String mchId;
    private final String notifyUrl;

    public WeChatPayAdapter(WeChatPayClient client, WeChatPayVerifier verifier, AesGcmDecryptor decryptor,
                            String appId, String mchId, String notifyUrl) {
        this.client = client;
        this.verifier = verifier;
        this.decryptor = decryptor;
        this.mapper = WeChatPayJson.mapper();
        this.appId = appId;
        this.mchId = mchId;
        this.notifyUrl = notifyUrl;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderPaymentSession createPayment(ProviderPaymentRequest request) {
        if (!CURRENCY.equals(request.amount().currency())) {
            throw new ProviderException(PROVIDER_NAME, "UNSUPPORTED_CURRENCY",
                    "WeChat Pay Native payments are settled in CNY, got " + request.amount().currency());
        }
        NativePrepayRequest body = new NativePrepayRequest(
                appId,
                mchId,
                request.description(),
                request.paymentReference(),
                request.expiresAt() == null ? null : RFC3339.format(request.expiresAt().atZone(CHINA)),
                notifyUrl,
                new Amount(Math.toIntExact(request.amount().amountMinor()), CURRENCY));

        NativePrepayResponse response = read(client.post(NATIVE_PREPAY_PATH, body), NativePrepayResponse.class);
        if (response.codeUrl() == null || response.codeUrl().isBlank()) {
            throw new ProviderException(PROVIDER_NAME, "INVALID_RESPONSE", "WeChat Pay response has no code_url");
        }
        return new ProviderPaymentSession(new PaymentAction(PaymentAction.Type.QR_CODE, response.codeUrl()));
    }

    @Override
    public ProviderPaymentUpdate queryPayment(String paymentReference) {
        String url = OUT_TRADE_NO_PATH + encode(paymentReference) + "?mchid=" + encode(mchId);
        return toUpdate(read(client.get(url), Transaction.class));
    }

    @Override
    public void cancelPayment(String paymentReference) {
        client.post(OUT_TRADE_NO_PATH + encode(paymentReference) + "/close", new CloseRequest(mchId));
    }

    @Override
    public Optional<ProviderPaymentUpdate> parseNotification(ProviderNotification notification) {
        try {
            verifier.verify(
                    notification.header(WeChatPayVerifier.HEADER_SERIAL),
                    notification.header(WeChatPayVerifier.HEADER_TIMESTAMP),
                    notification.header(WeChatPayVerifier.HEADER_NONCE),
                    notification.header(WeChatPayVerifier.HEADER_SIGNATURE),
                    notification.body());

            Notification envelope = mapper.readValue(notification.body(), Notification.class);
            EncryptedResource resource = envelope.resource();
            if (resource == null || !"transaction".equals(resource.originalType())) {
                return Optional.empty();
            }
            if (!"AEAD_AES_256_GCM".equals(resource.algorithm())) {
                throw new InvalidNotificationException(PROVIDER_NAME, "Unsupported algorithm " + resource.algorithm());
            }
            String plaintext = decryptor.decrypt(resource.associatedData(), resource.nonce(), resource.ciphertext());
            Transaction transaction = mapper.readValue(plaintext, Transaction.class);
            if (!mchId.equals(transaction.mchid()) || !appId.equals(transaction.appid())) {
                throw new InvalidNotificationException(PROVIDER_NAME, "Notification is for a different merchant or app");
            }
            return Optional.of(toUpdate(transaction));
        } catch (WeChatPaySecurityException e) {
            throw new InvalidNotificationException(PROVIDER_NAME, e.getMessage(), e);
        } catch (IOException e) {
            throw new InvalidNotificationException(PROVIDER_NAME, "Malformed notification payload", e);
        }
    }

    private ProviderPaymentUpdate toUpdate(Transaction tx) {
        PaymentStatus status = WeChatPayStatusMapper.toPaymentStatus(tx.tradeState());
        Money amount = tx.amount() == null || tx.amount().total() == null
                ? null
                : new Money(tx.amount().total(), tx.amount().currency() == null ? CURRENCY : tx.amount().currency());
        String reason = status == PaymentStatus.FAILED || status == PaymentStatus.CANCELLED ? tx.tradeStateDesc() : null;
        return new ProviderPaymentUpdate(tx.outTradeNo(), status, tx.transactionId(), amount,
                parseTime(tx.successTime()), reason);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new ProviderException(PROVIDER_NAME, "INVALID_RESPONSE", "Unreadable WeChat Pay response", e);
        }
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

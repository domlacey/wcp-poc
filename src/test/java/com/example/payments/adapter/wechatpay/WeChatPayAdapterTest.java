package com.example.payments.adapter.wechatpay;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.core.domain.Money;
import com.example.payments.core.domain.PaymentAction;
import com.example.payments.core.domain.PaymentStatus;
import com.example.payments.core.provider.InvalidNotificationException;
import com.example.payments.core.provider.ProviderException;
import com.example.payments.core.provider.ProviderNotification;
import com.example.payments.core.provider.ProviderPaymentRequest;
import com.example.payments.core.provider.ProviderPaymentSession;
import com.example.payments.core.provider.ProviderPaymentUpdate;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Exercises the adapter against a WireMock server that behaves like WeChat Pay API v3. */
class WeChatPayAdapterTest {

    private static final WeChatPayTestSupport WECHAT = new WeChatPayTestSupport();
    private static final WireMockServer server = new WireMockServer(options().dynamicPort());
    private static final String REF = "8f14e45fceea167a5a36dedd4bea2543";

    private WeChatPayAdapter adapter;

    @BeforeAll
    static void start() {
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        Clock clock = Clock.systemUTC();
        WeChatPayVerifier verifier = new WeChatPayVerifier(WeChatPayTestSupport.PLATFORM_KEY_ID,
                PemKeys.publicKey(WECHAT.platformPublicKeyPem()), clock);
        WeChatPaySigner signer = new WeChatPaySigner(WeChatPayTestSupport.MCH_ID, WeChatPayTestSupport.MERCHANT_SERIAL,
                PemKeys.privateKey(WECHAT.merchantPrivateKeyPem()), clock);
        WeChatPayClient client = new WeChatPayClient(URI.create(server.baseUrl()), HttpClient.newHttpClient(),
                signer, verifier, WeChatPayJson.mapper(), Duration.ofSeconds(5));
        adapter = new WeChatPayAdapter(client, verifier, new AesGcmDecryptor(WeChatPayTestSupport.API_V3_KEY),
                WeChatPayTestSupport.APP_ID, WeChatPayTestSupport.MCH_ID, WeChatPayTestSupport.NOTIFY_URL);
    }

    @Nested
    class CreatePayment {

        private final ProviderPaymentRequest request = new ProviderPaymentRequest(
                REF, new Money(1999, "CNY"), "Coffee beans 1kg", Instant.parse("2026-10-14T04:30:00Z"));

        @Test
        void placesSignedNativeOrderAndReturnsQrCode() {
            server.stubFor(post("/v3/pay/transactions/native").willReturn(
                    WECHAT.signedResponse(200, "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=p4lpSuKzz\"}")));

            ProviderPaymentSession session = adapter.createPayment(request);

            assertThat(session.nextAction())
                    .isEqualTo(new PaymentAction(PaymentAction.Type.QR_CODE, "weixin://wxpay/bizpayurl?pr=p4lpSuKzz"));

            server.verify(postRequestedFor(urlEqualTo("/v3/pay/transactions/native"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("Accept", equalTo("application/json"))
                    .withRequestBody(equalToJson("""
                            {"appid":"wxd678efh567hg6787","mchid":"1900000001","description":"Coffee beans 1kg",
                             "out_trade_no":"8f14e45fceea167a5a36dedd4bea2543",
                             "time_expire":"2026-10-14T12:30:00+08:00",
                             "notify_url":"https://pay.example.com/api/webhooks/wechatpay",
                             "amount":{"total":1999,"currency":"CNY"}}""")));
            assertMerchantSignatureValid(onlyRequest(), "POST", "/v3/pay/transactions/native");
        }

        @Test
        void rejectsNonCnyCurrencyWithoutCallingWeChatPay() {
            ProviderPaymentRequest usd = new ProviderPaymentRequest(REF, new Money(100, "USD"), "x", null);

            assertThatThrownBy(() -> adapter.createPayment(usd))
                    .isInstanceOf(ProviderException.class)
                    .extracting("code").isEqualTo("UNSUPPORTED_CURRENCY");
            assertThat(server.getAllServeEvents()).isEmpty();
        }

        @Test
        void surfacesWeChatPayErrorCode() {
            server.stubFor(post("/v3/pay/transactions/native").willReturn(WECHAT.signedResponse(400,
                    "{\"code\":\"PARAM_ERROR\",\"message\":\"appid和mch_id不匹配\"}")));

            assertThatThrownBy(() -> adapter.createPayment(request))
                    .isInstanceOfSatisfying(WeChatPayApiException.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(400);
                        assertThat(e.code()).isEqualTo("PARAM_ERROR");
                        assertThat(e.getMessage()).isEqualTo("appid和mch_id不匹配");
                    });
        }

        @Test
        void rejectsResponseWhoseSignatureDoesNotMatchBody() {
            Map<String, String> headersForOtherBody = WECHAT.signedHeaders("{\"code_url\":\"weixin://legit\"}");
            var response = com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
                    .withBody("{\"code_url\":\"weixin://attacker\"}");
            headersForOtherBody.forEach(response::withHeader);
            server.stubFor(post("/v3/pay/transactions/native").willReturn(response));

            assertThatThrownBy(() -> adapter.createPayment(request))
                    .isInstanceOf(WeChatPaySecurityException.class);
        }

        @Test
        void rejectsUnsignedResponse() {
            server.stubFor(post("/v3/pay/transactions/native").willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"code_url\":\"weixin://x\"}")));

            assertThatThrownBy(() -> adapter.createPayment(request))
                    .isInstanceOf(WeChatPaySecurityException.class)
                    .hasMessageContaining("Missing");
        }
    }

    @Nested
    class QueryPayment {

        @Test
        void queriesByOutTradeNoAndMapsTransaction() {
            String url = "/v3/pay/transactions/out-trade-no/" + REF + "?mchid=" + WeChatPayTestSupport.MCH_ID;
            server.stubFor(get(urlEqualTo(url)).willReturn(
                    WECHAT.signedResponse(200, WeChatPayTestSupport.transactionJson(REF, "SUCCESS", 1999))));

            ProviderPaymentUpdate update = adapter.queryPayment(REF);

            assertThat(update.paymentReference()).isEqualTo(REF);
            assertThat(update.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(update.providerTransactionId()).isEqualTo("4200000985202310141234567890");
            assertThat(update.amount()).isEqualTo(new Money(1999, "CNY"));
            assertThat(update.paidAt()).isEqualTo(Instant.parse("2026-10-14T11:30:00Z"));
            assertMerchantSignatureValid(onlyRequest(), "GET", url);
        }

        @Test
        void includesReasonForUnsuccessfulStates() {
            server.stubFor(get(urlEqualTo("/v3/pay/transactions/out-trade-no/" + REF + "?mchid=1900000001")).willReturn(
                    WECHAT.signedResponse(200, WeChatPayTestSupport.transactionJson(REF, "PAYERROR", 1999))));

            ProviderPaymentUpdate update = adapter.queryPayment(REF);

            assertThat(update.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(update.reason()).isEqualTo("desc");
        }

        @Test
        void surfacesOrderNotFound() {
            server.stubFor(get(urlEqualTo("/v3/pay/transactions/out-trade-no/" + REF + "?mchid=1900000001")).willReturn(
                    WECHAT.signedResponse(404, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}")));

            assertThatThrownBy(() -> adapter.queryPayment(REF))
                    .isInstanceOf(WeChatPayApiException.class)
                    .extracting("code").isEqualTo("ORDER_NOT_EXIST");
        }
    }

    @Nested
    class CancelPayment {

        @Test
        void closesOrderAtWeChatPay() {
            String url = "/v3/pay/transactions/out-trade-no/" + REF + "/close";
            server.stubFor(post(url).willReturn(WECHAT.signedResponse(204, "")));

            adapter.cancelPayment(REF);

            server.verify(postRequestedFor(urlEqualTo(url)).withRequestBody(equalToJson("{\"mchid\":\"1900000001\"}")));
            assertMerchantSignatureValid(onlyRequest(), "POST", url);
        }
    }

    @Nested
    class ParseNotification {

        @Test
        void verifiesDecryptsAndMapsPaymentNotification() {
            String body = WeChatPayTestSupport.paymentSuccessNotification(REF, 1999);

            Optional<ProviderPaymentUpdate> update = adapter.parseNotification(
                    new ProviderNotification(lowerCaseKeys(WECHAT.signedHeaders(body)), body));

            assertThat(update).hasValueSatisfying(u -> {
                assertThat(u.paymentReference()).isEqualTo(REF);
                assertThat(u.status()).isEqualTo(PaymentStatus.SUCCEEDED);
                assertThat(u.providerTransactionId()).isEqualTo("4200000985202310141234567890");
                assertThat(u.amount()).isEqualTo(new Money(1999, "CNY"));
            });
        }

        @Test
        void rejectsTamperedNotification() {
            String body = WeChatPayTestSupport.paymentSuccessNotification(REF, 1999);
            Map<String, String> headers = WECHAT.signedHeaders(body);

            assertThatThrownBy(() -> adapter.parseNotification(
                    new ProviderNotification(headers, body.replace("EV-", "XX-"))))
                    .isInstanceOf(InvalidNotificationException.class);
        }

        @Test
        void rejectsResourceEncryptedWithDifferentKey() {
            String body = WeChatPayTestSupport.notificationJson(
                    WeChatPayTestSupport.transactionJson(REF, "SUCCESS", 1999),
                    "vutsrqponmlkjihgfedcba9876543210", "transaction");

            assertThatThrownBy(() -> adapter.parseNotification(new ProviderNotification(WECHAT.signedHeaders(body), body)))
                    .isInstanceOf(InvalidNotificationException.class)
                    .hasMessageContaining("decrypt");
        }

        @Test
        void rejectsNotificationForAnotherMerchant() {
            String tx = WeChatPayTestSupport.transactionJson(REF, "SUCCESS", 1999).replace("1900000001", "1900000999");
            String body = WeChatPayTestSupport.notificationJson(tx, WeChatPayTestSupport.API_V3_KEY, "transaction");

            assertThatThrownBy(() -> adapter.parseNotification(new ProviderNotification(WECHAT.signedHeaders(body), body)))
                    .isInstanceOf(InvalidNotificationException.class)
                    .hasMessageContaining("different merchant");
        }

        @Test
        void ignoresNonPaymentNotifications() {
            String body = WeChatPayTestSupport.notificationJson("{\"refund_status\":\"SUCCESS\"}",
                    WeChatPayTestSupport.API_V3_KEY, "refund");

            assertThat(adapter.parseNotification(new ProviderNotification(WECHAT.signedHeaders(body), body))).isEmpty();
        }
    }

    private static LoggedRequest onlyRequest() {
        assertThat(server.getAllServeEvents()).hasSize(1);
        return server.getAllServeEvents().get(0).getRequest();
    }

    private static void assertMerchantSignatureValid(LoggedRequest request, String method, String url) {
        assertThat(WeChatPayTestSupport.isValidMerchantSignature(request.getHeader("Authorization"), method, url,
                request.getBodyAsString(), WECHAT.merchantKeys.getPublic())).isTrue();
    }

    private static Map<String, String> lowerCaseKeys(Map<String, String> headers) {
        Map<String, String> lower = new java.util.HashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(), v));
        return lower;
    }
}

package com.example.payments.api;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payments.adapter.wechatpay.WeChatPayTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end flow through the real Spring context: Payment API -> PaymentService -> WeChatPayAdapter ->
 * (WireMock playing WeChat Pay API v3), plus WeChat Pay calling back into the webhook endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentFlowIntegrationTest {

    private static final WeChatPayTestSupport WECHAT = new WeChatPayTestSupport();
    private static final WireMockServer wechatPay = new WireMockServer(options().dynamicPort());

    static {
        wechatPay.start();
    }

    @DynamicPropertySource
    static void wechatPayProperties(DynamicPropertyRegistry registry) {
        registry.add("wechatpay.enabled", () -> "true");
        registry.add("wechatpay.base-url", wechatPay::baseUrl);
        registry.add("wechatpay.mch-id", () -> WeChatPayTestSupport.MCH_ID);
        registry.add("wechatpay.app-id", () -> WeChatPayTestSupport.APP_ID);
        registry.add("wechatpay.merchant-serial-number", () -> WeChatPayTestSupport.MERCHANT_SERIAL);
        registry.add("wechatpay.merchant-private-key", WECHAT::merchantPrivateKeyPem);
        registry.add("wechatpay.api-v3-key", () -> WeChatPayTestSupport.API_V3_KEY);
        registry.add("wechatpay.platform-public-key-id", () -> WeChatPayTestSupport.PLATFORM_KEY_ID);
        registry.add("wechatpay.platform-public-key", WECHAT::platformPublicKeyPem);
        registry.add("wechatpay.notify-url", () -> WeChatPayTestSupport.NOTIFY_URL);
    }

    @AfterAll
    static void stopWeChatPay() {
        wechatPay.stop();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void resetWeChatPay() {
        wechatPay.resetAll();
        wechatPay.stubFor(post("/v3/pay/transactions/native").willReturn(
                WECHAT.signedResponse(200, "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=p4lpSuKzz\"}")));
    }

    @Test
    void customerPaysAndWeChatPayNotifiesPlatform() throws Exception {
        // 1. Merchant creates a payment -> customer gets a QR code to scan in WeChat.
        String id = createPayment(1999)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/payments/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.provider").value("wechatpay"))
                .andExpect(jsonPath("$.nextAction.type").value("QR_CODE"))
                .andExpect(jsonPath("$.nextAction.value").value("weixin://wxpay/bizpayurl?pr=p4lpSuKzz"))
                .andReturn().getResponse().getContentAsString()
                .transform(this::paymentId);

        // 2. Customer pays; WeChat Pay posts a signed, encrypted notification.
        String notification = WeChatPayTestSupport.paymentSuccessNotification(id, 1999);
        mvc.perform(webhook(notification, WECHAT.signedHeaders(notification))).andExpect(status().isNoContent());

        // 3. Merchant sees the payment as succeeded.
        mvc.perform(request(HttpMethod.GET, "/api/payments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerTransactionId").value("4200000985202310141234567890"))
                .andExpect(jsonPath("$.paidAt").value("2026-10-14T11:30:00Z"))
                .andExpect(jsonPath("$.nextAction").doesNotExist());

        // 4. WeChat Pay retries the same notification: acknowledged again, nothing changes.
        mvc.perform(webhook(notification, WECHAT.signedHeaders(notification))).andExpect(status().isNoContent());
        mvc.perform(request(HttpMethod.GET, "/api/payments/" + id)).andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void forgedNotificationIsRejected() throws Exception {
        String id = paymentId(createPayment(1999).andReturn().getResponse().getContentAsString());
        String notification = WeChatPayTestSupport.paymentSuccessNotification(id, 1999);
        Map<String, String> forgedHeaders = new WeChatPayTestSupport().signedHeaders(notification);

        mvc.perform(webhook(notification, forgedHeaders))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION"));
        mvc.perform(request(HttpMethod.GET, "/api/payments/" + id)).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void merchantRefreshesStatusWhenNotificationIsMissing() throws Exception {
        String id = paymentId(createPayment(1999).andReturn().getResponse().getContentAsString());
        wechatPay.stubFor(get(urlPathMatching("/v3/pay/transactions/out-trade-no/" + id)).willReturn(
                WECHAT.signedResponse(200, WeChatPayTestSupport.transactionJson(id, "SUCCESS", 1999))));

        mvc.perform(request(HttpMethod.POST, "/api/payments/" + id + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void merchantCancelsUnpaidPayment() throws Exception {
        String id = paymentId(createPayment(1999).andReturn().getResponse().getContentAsString());
        String closeUrl = "/v3/pay/transactions/out-trade-no/" + id + "/close";
        wechatPay.stubFor(post(closeUrl).willReturn(WECHAT.signedResponse(204, "")));

        mvc.perform(request(HttpMethod.POST, "/api/payments/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        wechatPay.verify(postRequestedFor(urlEqualTo(closeUrl)));

        mvc.perform(request(HttpMethod.POST, "/api/payments/" + id + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_PAYMENT_STATE"));
    }

    @Test
    void weChatPayErrorIsReportedAsBadGateway() throws Exception {
        wechatPay.stubFor(post("/v3/pay/transactions/native").willReturn(WECHAT.signedResponse(403,
                "{\"code\":\"NO_AUTH\",\"message\":\"商户无权限\"}")));

        createPayment(1999)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("NO_AUTH"));
    }

    @Test
    void invalidRequestIsRejectedBeforeReachingWeChatPay() throws Exception {
        mvc.perform(request(HttpMethod.POST, "/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantOrderId\":\"o-1\",\"amount\":0,\"currency\":\"cny\",\"provider\":\"wechatpay\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(wechatPay.getAllServeEvents()).isEmpty();
    }

    private ResultActions createPayment(long amount) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "merchantOrderId", "order-" + UUID.randomUUID(),
                "amount", amount,
                "currency", "CNY",
                "description", "Coffee beans 1kg",
                "provider", "wechatpay"));
        return mvc.perform(request(HttpMethod.POST, "/api/payments").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static MockHttpServletRequestBuilder webhook(String body, Map<String, String> headers) {
        MockHttpServletRequestBuilder request = request(HttpMethod.POST, "/api/webhooks/wechatpay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.forEach(request::header);
        return request;
    }

    private String paymentId(String responseBody) {
        try {
            return json.readTree(responseBody).get("id").asText();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

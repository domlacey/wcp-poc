package com.example.payments.adapter.wechatpay;

import com.example.payments.adapter.wechatpay.WeChatPayModels.ErrorBody;
import com.example.payments.core.provider.ProviderException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal HTTP transport for WeChat Pay API v3: signs every request and verifies the signature of every
 * successful response before handing the body back.
 */
public class WeChatPayClient {

    private static final String USER_AGENT = "payments-poc-wechatpay-adapter/0.1 (Java)";

    private final URI baseUrl;
    private final HttpClient httpClient;
    private final WeChatPaySigner signer;
    private final WeChatPayVerifier verifier;
    private final ObjectMapper mapper;
    private final Duration readTimeout;

    public WeChatPayClient(URI baseUrl, HttpClient httpClient, WeChatPaySigner signer, WeChatPayVerifier verifier,
                           ObjectMapper mapper, Duration readTimeout) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.signer = signer;
        this.verifier = verifier;
        this.mapper = mapper;
        this.readTimeout = readTimeout;
    }

    public String get(String canonicalUrl) {
        return execute("GET", canonicalUrl, "");
    }

    public String post(String canonicalUrl, Object payload) {
        try {
            return execute("POST", canonicalUrl, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize WeChat Pay request", e);
        }
    }

    private String execute(String method, String canonicalUrl, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(canonicalUrl))
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", signer.authorization(method, canonicalUrl, body));
        if ("GET".equals(method)) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderException(WeChatPayAdapter.PROVIDER_NAME, "NETWORK_ERROR",
                    "WeChat Pay request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(WeChatPayAdapter.PROVIDER_NAME, "INTERRUPTED", "WeChat Pay request interrupted", e);
        }

        int status = response.statusCode();
        String responseBody = response.body() == null ? "" : response.body();
        if (status >= 200 && status < 300) {
            verifier.verify(
                    header(response, WeChatPayVerifier.HEADER_SERIAL),
                    header(response, WeChatPayVerifier.HEADER_TIMESTAMP),
                    header(response, WeChatPayVerifier.HEADER_NONCE),
                    header(response, WeChatPayVerifier.HEADER_SIGNATURE),
                    responseBody);
            return responseBody;
        }
        throw toApiException(status, responseBody);
    }

    private WeChatPayApiException toApiException(int status, String body) {
        try {
            ErrorBody error = mapper.readValue(body, ErrorBody.class);
            if (error.code() != null) {
                return new WeChatPayApiException(status, error.code(), error.message());
            }
        } catch (IOException ignored) {
            // fall through to a generic error
        }
        return new WeChatPayApiException(status, "HTTP_" + status, "Unexpected WeChat Pay response");
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}

package com.example.payments.demo;

import com.example.payments.adapter.wechatpay.WeChatPayTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local stand-in for WeChat Pay API v3, for demos only (see scripts/demo.sh).
 *
 * <p>Implements the three endpoints the adapter calls, checks the merchant signature on every request,
 * signs every response, and can play the customer paying, which makes it send a signed, encrypted
 * notification to the order's notify_url. Writes throwaway keys and a matching env file to the output dir.
 *
 * <pre>
 * POST /simulator/pay/{out_trade_no}[?notify=false]  customer pays (and WeChat Pay notifies)
 * POST /simulator/resend/{out_trade_no}              WeChat Pay retries the notification
 * POST /simulator/forge/{out_trade_no}               attacker sends a notification signed with the wrong key
 * </pre>
 */
public final class WeChatPaySimulator {

    private static final Pattern ORDER_PATH = Pattern.compile("/v3/pay/transactions/out-trade-no/([^/]+)(/close)?");
    private static final Pattern SIM_PATH = Pattern.compile("/simulator/(pay|resend|forge)/([^/]+)");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    private final WeChatPayTestSupport wechat = new WeChatPayTestSupport();
    private final WeChatPayTestSupport impostor = new WeChatPayTestSupport();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private static final class Order {
        final String outTradeNo;
        final long total;
        final String notifyUrl;
        volatile String state = "NOTPAY";
        volatile String transactionId;
        volatile String successTime;

        Order(String outTradeNo, long total, String notifyUrl) {
            this.outTradeNo = outTradeNo;
            this.total = total;
            this.notifyUrl = notifyUrl;
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8089;
        Path dir = Path.of(args.length > 1 ? args[1] : "target/demo").toAbsolutePath();
        String notifyUrl = args.length > 2 ? args[2] : "http://localhost:8080/api/webhooks/wechatpay";
        new WeChatPaySimulator().start(port, dir, notifyUrl);
    }

    private void start(int port, Path dir, String notifyUrl) throws IOException {
        Files.createDirectories(dir);
        Path merchantKey = Files.writeString(dir.resolve("apiclient_key.pem"), wechat.merchantPrivateKeyPem());
        Path platformKey = Files.writeString(dir.resolve("pub_key.pem"), wechat.platformPublicKeyPem());
        Files.writeString(dir.resolve("wechatpay.env"), String.join("\n",
                "WECHATPAY_ENABLED=true",
                "WECHATPAY_BASE_URL=http://localhost:" + port,
                "WECHATPAY_MCH_ID=" + WeChatPayTestSupport.MCH_ID,
                "WECHATPAY_APP_ID=" + WeChatPayTestSupport.APP_ID,
                "WECHATPAY_MERCHANT_SERIAL_NUMBER=" + WeChatPayTestSupport.MERCHANT_SERIAL,
                "WECHATPAY_MERCHANT_PRIVATE_KEY_PATH=" + merchantKey,
                "WECHATPAY_API_V3_KEY=" + WeChatPayTestSupport.API_V3_KEY,
                "WECHATPAY_PLATFORM_PUBLIC_KEY_ID=" + WeChatPayTestSupport.PLATFORM_KEY_ID,
                "WECHATPAY_PLATFORM_PUBLIC_KEY_PATH=" + platformKey,
                "WECHATPAY_NOTIFY_URL=" + notifyUrl,
                ""));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (Exception e) {
                log("ERROR " + e);
                respond(exchange, 500, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"" + e.getMessage() + "\"}", false);
            } finally {
                exchange.close();
            }
        });
        server.start();
        log("listening on http://localhost:" + port + " (mchid " + WeChatPayTestSupport.MCH_ID + ")");
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        URI uri = ex.getRequestURI();
        String path = uri.getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        Matcher sim = SIM_PATH.matcher(path);
        if (sim.matches()) {
            simulator(ex, sim.group(1), sim.group(2), uri.getQuery());
            return;
        }

        String canonicalUrl = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !WeChatPayTestSupport.isValidMerchantSignature(auth, method, canonicalUrl, body,
                wechat.merchantKeys.getPublic())) {
            log(method + " " + canonicalUrl + "  ✘ merchant signature INVALID -> 401");
            respond(ex, 401, "{\"code\":\"SIGN_ERROR\",\"message\":\"签名错误\"}", true);
            return;
        }

        if ("POST".equals(method) && "/v3/pay/transactions/native".equals(path)) {
            JsonNode req = json.readTree(body);
            Order order = new Order(req.path("out_trade_no").asText(), req.path("amount").path("total").asLong(),
                    req.path("notify_url").asText());
            orders.put(order.outTradeNo, order);
            String codeUrl = "weixin://wxpay/bizpayurl?pr=" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            log("POST /v3/pay/transactions/native  ✔ merchant signature valid  -> order " + order.outTradeNo
                    + " ¥" + yuan(order.total) + " NOTPAY, code_url " + codeUrl);
            respond(ex, 200, "{\"code_url\":\"" + codeUrl + "\"}", true);
            return;
        }

        Matcher m = ORDER_PATH.matcher(path);
        if (m.matches()) {
            Order order = orders.get(m.group(1));
            if (order == null) {
                log(method + " " + canonicalUrl + "  ✔ signature valid  -> 404 ORDER_NOT_EXIST");
                respond(ex, 404, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}", true);
            } else if (m.group(2) != null && "POST".equals(method)) {
                if ("SUCCESS".equals(order.state)) {
                    log("POST close " + order.outTradeNo + "  -> 400, already paid");
                    respond(ex, 400, "{\"code\":\"INVALID_REQUEST\",\"message\":\"订单已支付\"}", true);
                } else {
                    order.state = "CLOSED";
                    log("POST " + path + "  ✔ merchant signature valid  -> order CLOSED (204)");
                    respond(ex, 204, "", true);
                }
            } else if ("GET".equals(method)) {
                log("GET " + canonicalUrl + "  ✔ merchant signature valid  -> trade_state " + order.state);
                respond(ex, 200, transaction(order), true);
            } else {
                respond(ex, 405, "", false);
            }
            return;
        }
        respond(ex, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"unknown endpoint\"}", false);
    }

    private void simulator(HttpExchange ex, String action, String outTradeNo, String query) throws IOException {
        Order order = orders.get(outTradeNo);
        if (order == null) {
            respond(ex, 404, "{\"error\":\"unknown order\"}", false);
            return;
        }
        String result;
        switch (action) {
            case "pay" -> {
                order.state = "SUCCESS";
                order.transactionId = "42000" + System.currentTimeMillis() + "0000";
                order.successTime = OffsetDateTime.now(CHINA).truncatedTo(ChronoUnit.SECONDS)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                log("customer paid order " + outTradeNo + " in WeChat (transaction_id " + order.transactionId + ")");
                result = "query=notify=false".equals("query=" + query)
                        ? "paid, notification NOT sent"
                        : "paid, " + notify(order, wechat, "sent notification");
            }
            case "resend" -> result = notify(order, wechat, "re-sent the same notification (retry)");
            case "forge" -> result = notify(order, impostor, "attacker sent a notification signed with the WRONG key");
            default -> throw new IllegalArgumentException(action);
        }
        respond(ex, 200, "{\"result\":\"" + result + "\"}", false);
    }

    private String notify(Order order, WeChatPayTestSupport signer, String description) {
        Order snapshot = order;
        if (signer == impostor && !"SUCCESS".equals(order.state)) {
            snapshot = new Order(order.outTradeNo, order.total, order.notifyUrl);
            snapshot.state = "SUCCESS";
            snapshot.transactionId = "4200000000000000000000000000";
            snapshot.successTime = OffsetDateTime.now(CHINA).truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        String body = WeChatPayTestSupport.notificationJson(transaction(snapshot), WeChatPayTestSupport.API_V3_KEY,
                "transaction");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(order.notifyUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        signer.signedHeaders(body).forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String outcome = description + " (encrypted TRANSACTION.SUCCESS) -> platform answered HTTP "
                    + response.statusCode() + (response.body().isEmpty() ? "" : " " + response.body());
            log(outcome);
            return outcome.replace("\"", "'");
        } catch (IOException | InterruptedException e) {
            log("notification failed: " + e);
            return "notification failed: " + e.getMessage();
        }
    }

    private String transaction(Order order) {
        ObjectNode tx = json.createObjectNode()
                .put("appid", WeChatPayTestSupport.APP_ID)
                .put("mchid", WeChatPayTestSupport.MCH_ID)
                .put("out_trade_no", order.outTradeNo)
                .put("trade_type", "NATIVE")
                .put("trade_state", order.state)
                .put("trade_state_desc", switch (order.state) {
                    case "SUCCESS" -> "支付成功";
                    case "CLOSED" -> "订单已关闭";
                    default -> "订单未支付";
                });
        if ("SUCCESS".equals(order.state)) {
            tx.put("transaction_id", order.transactionId).put("success_time", order.successTime).put("bank_type", "OTHERS");
        }
        tx.putObject("amount").put("total", order.total).put("payer_total", order.total)
                .put("currency", "CNY").put("payer_currency", "CNY");
        return tx.toString();
    }

    private void respond(HttpExchange ex, int status, String body, boolean signed) throws IOException {
        if (signed) {
            wechat.signedHeaders(body).forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            ex.getResponseHeaders().add("Content-Type", "application/json");
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String yuan(long fen) {
        return String.format("%d.%02d", fen / 100, fen % 100);
    }

    private static void log(String message) {
        System.out.println(message);
        System.out.flush();
    }
}

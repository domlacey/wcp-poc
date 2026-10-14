# WeChat Pay API v3 adapter

Package `com.example.payments.adapter.wechatpay`. This is the only code that knows about WeChat Pay.

| Class | Responsibility |
|---|---|
| `WeChatPayAdapter` | Implements `PaymentProvider`: builds API v3 payloads, maps responses and `trade_state` to platform types, handles notifications |
| `WeChatPayClient` | HTTP transport (`java.net.http`): signs each request, verifies each 2xx response signature, maps error bodies to `WeChatPayApiException` |
| `WeChatPaySigner` | Builds the `Authorization` header (SHA256withRSA with the merchant API private key) |
| `WeChatPayVerifier` | Verifies `Wechatpay-Signature` on responses and notifications with the WeChat Pay public key |
| `AesGcmDecryptor` | Decrypts `AEAD_AES_256_GCM` notification resources with the APIv3 key |
| `WeChatPayModels` | API v3 JSON records (snake_case via `WeChatPayJson`) |
| `WeChatPayStatusMapper` | `trade_state` → `PaymentStatus` |
| `PemKeys` | Loads the merchant private key, WeChat Pay public key, or platform certificate from PEM |
| `WeChatPayProperties` / `WeChatPayConfiguration` | `wechatpay.*` settings and Spring wiring |

## Configuration

All settings use the prefix `wechatpay` (env vars in `application.yml`). Keys can be given inline
(`merchant-private-key`, `platform-public-key`) or as files (`...-path`). Inline values win.

| Property | Env var | Where it comes from |
|---|---|---|
| `enabled` | `WECHATPAY_ENABLED` | `true` to register the adapter |
| `base-url` | `WECHATPAY_BASE_URL` | `https://api.mch.weixin.qq.com` (default) |
| `mch-id` | `WECHATPAY_MCH_ID` | Merchant id (商户号) |
| `app-id` | `WECHATPAY_APP_ID` | AppID bound to the merchant |
| `merchant-serial-number` | `WECHATPAY_MERCHANT_SERIAL_NUMBER` | Serial number of the merchant API certificate (商户API证书序列号) |
| `merchant-private-key-path` | `WECHATPAY_MERCHANT_PRIVATE_KEY_PATH` | `apiclient_key.pem` (PKCS#8) |
| `api-v3-key` | `WECHATPAY_API_V3_KEY` | 32-character APIv3 key set in the merchant platform |
| `platform-public-key-id` | `WECHATPAY_PLATFORM_PUBLIC_KEY_ID` | WeChat Pay public key id (`PUB_KEY_ID_...`), or platform certificate serial number |
| `platform-public-key-path` | `WECHATPAY_PLATFORM_PUBLIC_KEY_PATH` | `pub_key.pem`, or the platform certificate PEM |
| `notify-url` | `WECHATPAY_NOTIFY_URL` | Public HTTPS URL of `/api/webhooks/wechatpay` |
| `connect-timeout` / `read-timeout` | – | Defaults `5s` / `10s` |

Missing required values stop startup with `Missing required property wechatpay.<name>`.

## API v3 endpoints used

| Operation | Request | Success response |
|---|---|---|
| Native order | `POST /v3/pay/transactions/native` with `appid, mchid, description, out_trade_no, time_expire, notify_url, amount{total, currency}` | `200 {"code_url": "weixin://..."}` |
| Query | `GET /v3/pay/transactions/out-trade-no/{out_trade_no}?mchid={mchid}` | `200` transaction (`trade_state`, `transaction_id`, `amount`, `success_time`, ...) |
| Close | `POST /v3/pay/transactions/out-trade-no/{out_trade_no}/close` with `{"mchid"}` | `204` |
| Payment notification | WeChat Pay → `notify_url` | we reply `204`; any non-2xx makes WeChat Pay retry |

Details:

- `amount.total` is an integer in fen. Only `CNY` is accepted. Other currencies fail with
  `UNSUPPORTED_CURRENCY` before any HTTP call.
- `time_expire` is RFC 3339 in China time, e.g. `2026-10-14T12:30:00+08:00`. It comes from
  `payments.expiry` (default 30 minutes).
- `out_trade_no` is the platform payment id (32 hex characters).

## Request signing

Every request carries:

```
Authorization: WECHATPAY2-SHA256-RSA2048 mchid="1900000001",nonce_str="<32 chars>",timestamp="<epoch s>",serial_no="<merchant cert serial>",signature="<base64>"
```

`signature` is SHA256withRSA, with the merchant API private key, over:

```
HTTP_METHOD\n
URL path + query (exactly as sent)\n
timestamp\n
nonce_str\n
request body (empty for GET)\n
```

The signed URL and body are exactly the bytes that are sent. `WeChatPayClient` serializes the body once
and uses that same string for both signing and sending.

## Response and notification verification

WeChat Pay signs responses and notifications with its own key. `WeChatPayVerifier` checks:

1. `Wechatpay-Serial` matches the configured `platform-public-key-id`
2. `Wechatpay-Timestamp` is within ±5 minutes of now (limits replay)
3. `Wechatpay-Signature` is valid SHA256withRSA over `timestamp\nnonce\nbody\n` with the WeChat Pay
   public key

Every 2xx response is verified, and an unsigned or wrongly signed 2xx response fails with
`WeChatPaySecurityException` (`502 SECURITY_ERROR`), so a spoofed `code_url` is never shown to a
customer. Error responses are not verified. They only ever lead to a failure.

## Notification handling

```json
{
  "id": "EV-2018022511223320873",
  "event_type": "TRANSACTION.SUCCESS",
  "resource_type": "encrypt-resource",
  "resource": {
    "original_type": "transaction",
    "algorithm": "AEAD_AES_256_GCM",
    "ciphertext": "...",
    "associated_data": "transaction",
    "nonce": "..."
  }
}
```

`WeChatPayAdapter.parseNotification`:

1. Verifies the signature over the raw body. `WebhookController` passes the body bytes through
   unchanged.
2. Returns empty (ignored, still acknowledged) if `original_type` is not `transaction` (e.g. refunds).
3. Decrypts `ciphertext` with AES-256-GCM: key = APIv3 key, IV = `nonce`, AAD = `associated_data`.
   The last 16 bytes of the ciphertext are the GCM tag.
4. Checks that `mchid` and `appid` in the decrypted transaction match the configuration.
5. Maps the transaction to a `ProviderPaymentUpdate`. The core then checks the amount and applies the
   update idempotently.

Any failure becomes `InvalidNotificationException` → `400`, and WeChat Pay retries later.

## Status mapping

| `trade_state` | Meaning | `PaymentStatus` |
|---|---|---|
| `SUCCESS` | Paid | `SUCCEEDED` |
| `REFUND` | Paid, refund requested (refunds out of scope) | `SUCCEEDED` |
| `NOTPAY` | Not paid yet | `PENDING` |
| `USERPAYING` | Customer is paying (e.g. entering a password) | `PENDING` |
| `CLOSED` | Closed | `CANCELLED` |
| `REVOKED` | Revoked (payment-code flows) | `CANCELLED` |
| `PAYERROR` | Payment failed | `FAILED` |
| anything else | – | `PENDING` (no change) |

For `FAILED` and `CANCELLED`, `trade_state_desc` becomes the payment's `failureReason`.

## Errors

| Exception | When | API result |
|---|---|---|
| `WeChatPayApiException` | WeChat Pay non-2xx, e.g. `400 PARAM_ERROR`, `403 NO_AUTH`, `404 ORDER_NOT_EXIST` | `502`, `code` = WeChat Pay code |
| `WeChatPaySecurityException` | Response signature missing or invalid, or decryption failed | `502 SECURITY_ERROR` |
| `ProviderException` `NETWORK_ERROR` | I/O error or timeout | `502` |
| `ProviderException` `UNSUPPORTED_CURRENCY` | Non-CNY payment | `502` |
| `InvalidNotificationException` | Notification failed verification, decryption, or merchant checks | `400` |

All of these extend the core `ProviderException`, so the core handles them without knowing about
WeChat Pay.

## Tests

| Test | Covers |
|---|---|
| `WeChatPaySignerTest` | Header format, signature covers method, URL, body; fresh nonce per request |
| `WeChatPayVerifierTest` | Valid signature, tampered body, unknown serial, stale timestamp, wrong key, missing headers |
| `AesGcmDecryptorTest` | Decrypts, wrong AAD, wrong key, key length |
| `WeChatPayStatusMapperTest` | Every `trade_state` |
| `WeChatPayAdapterTest` | Against WireMock acting as WeChat Pay: exact request JSON, merchant signature checked as WeChat Pay would, signed responses, error codes, spoofed or unsigned responses, query/close, notification decrypt and the rejection cases |
| `PaymentFlowIntegrationTest` | Full Spring context: create → notify → read, duplicate notification, forged notification, refresh, cancel, WeChat Pay error, validation |

`WeChatPayTestSupport` plays WeChat Pay in tests. It generates throwaway merchant and WeChat Pay RSA
key pairs, signs responses and notifications, and encrypts notification resources. No real
credentials are needed.

## Extending the adapter

- **JSAPI / Mini Program:** call `POST /v3/pay/transactions/jsapi` with `payer.openid`. The adapter
  then signs `appId\ntimeStamp\nnonceStr\nprepay_id=...\n` and returns those parameters as a new
  `PaymentAction.Type`. Only the core enum changes.
- **Refunds:** `POST /v3/refund/domestic/refunds`, and handle `original_type = refund` notifications in
  `parseNotification`.
- **Key rotation:** let `WeChatPayVerifier` hold a map of serial → public key so old and new keys are
  accepted during rotation.
- **Official SDK:** `com.github.wechatpay-apiv3:wechatpay-java` can replace `WeChatPayClient`,
  `WeChatPaySigner`, `WeChatPayVerifier`, and `AesGcmDecryptor`. `WeChatPayAdapter`'s mapping code and
  everything outside the package stay as they are.

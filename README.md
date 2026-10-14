# WeChat Pay API v3 – payment flow proof of concept (Java)

A small, working proof of concept showing how a Java payments platform can connect to
[WeChat Pay API v3](https://pay.weixin.qq.com/doc/v3/merchant/4012062524) through an isolated
provider adapter.

```
Payment API  ──▶  Core Payment Service  ──▶  WeChat Pay adapter  ◀──▶  WeChat Pay API v3
 (REST)            (provider-agnostic)        (all WeChat Pay code)
                                    ▲                                       │
                                    └──── webhook (signed + encrypted) ─────┘
```

The core service only knows the `PaymentProvider` interface. Everything WeChat Pay specific (request
signing, response and notification signature verification, AES-256-GCM decryption, payload formats,
status mapping) lives in `adapter/wechatpay`. Adding another provider means adding another adapter; the
core does not change. `ArchitectureTest` fails the build if that rule is broken.

**This is a proof of concept, not a production platform.** See [Out of scope](#out-of-scope).

## What it does

Uses WeChat Pay **Native** payments (the merchant shows a QR code, the customer scans it in WeChat):

| Step | Platform API | WeChat Pay API v3 |
|---|---|---|
| Create a payment, get a QR code | `POST /api/payments` | `POST /v3/pay/transactions/native` |
| Receive the payment result | `POST /api/webhooks/wechatpay` | WeChat Pay calls `notify_url` |
| Read a payment | `GET /api/payments/{id}` | – |
| Pull status if the notification is late | `POST /api/payments/{id}/refresh` | `GET /v3/pay/transactions/out-trade-no/{out_trade_no}` |
| Cancel an unpaid payment | `POST /api/payments/{id}/cancel` | `POST /v3/pay/transactions/out-trade-no/{out_trade_no}/close` |

Sequence diagrams for each flow: [docs/payment-flows.md](docs/payment-flows.md).

## Quick start

Requires Java 17+. The Maven wrapper downloads Maven on first use.

```bash
./mvnw test             # 56 tests: unit, adapter-vs-mock-WeChat-Pay, and end-to-end flow
./mvnw spring-boot:run  # starts on :8080 (no providers unless WeChat Pay is configured)
```

The tests need no WeChat Pay account. `PaymentFlowIntegrationTest` runs the full flow through the real
Spring context with WireMock acting as WeChat Pay: it checks request signatures, signs its responses,
and sends encrypted notifications, as WeChat Pay does.

## Running against WeChat Pay

You need a WeChat Pay merchant account with API v3 enabled, an AppID bound to it, and a public HTTPS URL
for notifications (for local testing, a tunnel such as ngrok to `/api/webhooks/wechatpay`).

```bash
export WECHATPAY_ENABLED=true
export WECHATPAY_MCH_ID=1900000001                      # 商户号
export WECHATPAY_APP_ID=wxd678efh567hg6787              # AppID bound to the merchant
export WECHATPAY_MERCHANT_SERIAL_NUMBER=5157F09E...     # 商户API证书序列号
export WECHATPAY_MERCHANT_PRIVATE_KEY_PATH=/secrets/apiclient_key.pem
export WECHATPAY_API_V3_KEY=...                         # 32-character APIv3 key
export WECHATPAY_PLATFORM_PUBLIC_KEY_ID=PUB_KEY_ID_...  # WeChat Pay public key id (or platform cert serial)
export WECHATPAY_PLATFORM_PUBLIC_KEY_PATH=/secrets/pub_key.pem
export WECHATPAY_NOTIFY_URL=https://pay.example.com/api/webhooks/wechatpay
./mvnw spring-boot:run
```

All settings and where to find them: [docs/wechatpay-adapter.md](docs/wechatpay-adapter.md#configuration).

## API example

```bash
# Create a payment (amount in fen: 1999 = ¥19.99)
curl -s localhost:8080/api/payments -H 'Content-Type: application/json' -d '{
  "merchantOrderId": "order-1001",
  "amount": 1999,
  "currency": "CNY",
  "description": "Coffee beans 1kg",
  "provider": "wechatpay"
}'
```

```json
{
  "id": "8f14e45fceea167a5a36dedd4bea2543",
  "merchantOrderId": "order-1001",
  "provider": "wechatpay",
  "status": "PENDING",
  "amount": 1999,
  "currency": "CNY",
  "description": "Coffee beans 1kg",
  "nextAction": { "type": "QR_CODE", "value": "weixin://wxpay/bizpayurl?pr=p4lpSuKzz" },
  "createdAt": "2026-10-14T11:00:00Z",
  "updatedAt": "2026-10-14T11:00:00Z"
}
```

Render `nextAction.value` as a QR code. After the customer pays, WeChat Pay notifies the webhook and
`GET /api/payments/{id}` returns `"status": "SUCCEEDED"` with `providerTransactionId` and `paidAt`.

Statuses: `PENDING`, `SUCCEEDED`, `FAILED`, `CANCELLED`. Errors are returned as
`{"code": "...", "message": "..."}`: `400` bad request or rejected notification, `404` unknown payment,
`409` duplicate merchant order or invalid state, `422` amount mismatch, `502` provider error (the
WeChat Pay error code, e.g. `PARAM_ERROR`, is passed through in `code`).

## Project layout

```
src/main/java/com/example/payments
├── api/                    REST controllers, request/response DTOs, error mapping
├── core/
│   ├── domain/             Payment, PaymentStatus (state machine), Money, PaymentAction
│   ├── provider/           PaymentProvider port + provider-neutral request/update types
│   └── service/            PaymentService (orchestration), PaymentRepository port
├── persistence/            In-memory PaymentRepository
├── config/                 Spring wiring for the core
└── adapter/wechatpay/      WeChat Pay API v3 adapter (only place that knows WeChat Pay)
```

## Documentation

- [docs/architecture.md](docs/architecture.md): layers, the provider port, payment state machine, adding a provider
- [docs/payment-flows.md](docs/payment-flows.md): sequence diagrams for create, notify, refresh, cancel, and failures
- [docs/wechatpay-adapter.md](docs/wechatpay-adapter.md): configuration, signing, verification, decryption, status mapping, errors, tests

## Design decisions

- **Hand-written signing instead of the official SDK.** The adapter uses only the JDK (`java.net.http`,
  `java.security`, `javax.crypto`) and Jackson, so the API v3 protocol is visible in about 300 lines.
  The official [wechatpay-java](https://github.com/wechatpay-apiv3/wechatpay-java) SDK could replace
  `WeChatPayClient`/`Signer`/`Verifier` without changes outside the adapter.
- **The platform payment id is the WeChat Pay `out_trade_no`.** Ids are 32 alphanumeric characters (fits
  WeChat Pay's 6–32 limit), so query, close and notifications are matched without storing provider ids.
- **The payment is saved before the provider is called**, so an early notification always finds it.
- **Notifications are idempotent** and also checked for merchant, AppID and amount, not just signature.
- **The provider is the source of truth for money:** a late `SUCCESS` moves a `CANCELLED`/`FAILED`
  payment to `SUCCEEDED`. `SUCCEEDED` never changes.

## Out of scope

Things a production integration would add, deliberately left out of this PoC:

- Durable storage, outbox/eventing, and distributed locking (the repository is in memory)
- JSAPI / Mini Program / H5 / App payment types (only Native is implemented)
- Refunds, refund notifications, bill download, and reconciliation
- Automatic platform certificate download and rotation (one configured WeChat Pay public key is used)
- Scheduled status polling and automatic close of expired orders
- Secrets management, merchant authentication on the Payment API, rate limiting, metrics, and tracing
- Multi-merchant / service-provider (服务商) mode

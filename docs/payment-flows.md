# Payment flows

All flows use WeChat Pay Native payments. `id` is the platform payment id, sent to WeChat Pay as
`out_trade_no`.

## 1. Create payment

```mermaid
sequenceDiagram
    autonumber
    participant M as Merchant client
    participant API as PaymentController
    participant S as PaymentService
    participant R as PaymentRepository
    participant A as WeChatPayAdapter
    participant WX as WeChat Pay API v3

    M->>API: POST /api/payments {merchantOrderId, amount, currency, description, provider}
    API->>S: createPayment(command)
    S->>R: insert(Payment PENDING)  [rejects duplicate merchantOrderId]
    S->>A: createPayment(id, amount, description, expiresAt)
    A->>WX: POST /v3/pay/transactions/native<br/>Authorization: WECHATPAY2-SHA256-RSA2048 ...
    WX-->>A: 200 {"code_url": "weixin://wxpay/bizpayurl?pr=..."} + Wechatpay-Signature
    A->>A: verify response signature
    A-->>S: PaymentAction(QR_CODE, code_url)
    S->>R: save
    S-->>API: Payment
    API-->>M: 201 {status: PENDING, nextAction: {type: QR_CODE, value: code_url}}
```

The payment is stored **before** WeChat Pay is called, so a fast notification can always find it.

## 2. Customer pays, WeChat Pay notifies

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer (WeChat)
    participant WX as WeChat Pay
    participant API as WebhookController
    participant S as PaymentService
    participant A as WeChatPayAdapter

    C->>WX: scan QR code and pay
    WX->>API: POST /api/webhooks/wechatpay<br/>Wechatpay-Serial/Timestamp/Nonce/Signature<br/>{event_type: TRANSACTION.SUCCESS, resource: {ciphertext...}}
    API->>S: handleNotification("wechatpay", headers, raw body)
    S->>A: parseNotification
    A->>A: verify signature (WeChat Pay public key, 5 min window)
    A->>A: AES-256-GCM decrypt resource with APIv3 key
    A->>A: check mchid + appid, map trade_state
    A-->>S: update(id, SUCCEEDED, transaction_id, amount, success_time)
    S->>S: check amount, apply transition (idempotent)
    S-->>API: done
    API-->>WX: 204 No Content
```

WeChat Pay retries until it gets a 2xx. Repeated notifications return 204 and change nothing.
Notifications that fail verification get `400 {"code":"INVALID_NOTIFICATION"}` and the payment is
not changed.

## 3. Refresh (notification late or missing)

```mermaid
sequenceDiagram
    participant M as Merchant client
    participant S as PaymentService
    participant A as WeChatPayAdapter
    participant WX as WeChat Pay API v3

    M->>S: POST /api/payments/{id}/refresh
    alt payment already final
        S-->>M: 200 current payment (WeChat Pay not called)
    else PENDING
        S->>A: queryPayment(id)
        A->>WX: GET /v3/pay/transactions/out-trade-no/{id}?mchid=...
        WX-->>A: 200 {trade_state: SUCCESS | NOTPAY | CLOSED | ...}
        A-->>S: mapped update
        S-->>M: 200 updated payment
    end
```

## 4. Cancel unpaid payment

```mermaid
sequenceDiagram
    participant M as Merchant client
    participant S as PaymentService
    participant A as WeChatPayAdapter
    participant WX as WeChat Pay API v3

    M->>S: POST /api/payments/{id}/cancel
    alt not PENDING
        S-->>M: 409 ILLEGAL_PAYMENT_STATE
    else PENDING
        S->>A: cancelPayment(id)
        A->>WX: POST /v3/pay/transactions/out-trade-no/{id}/close {"mchid": "..."}
        WX-->>A: 204 No Content
        S-->>M: 200 {status: CANCELLED}
    end
```

If the customer paid just before the close, WeChat Pay's success notification still moves the
payment to `SUCCEEDED` (see the state machine in [architecture.md](architecture.md#payment-lifecycle)).

## 5. Failures

| Situation | Result |
|---|---|
| Invalid request (amount ≤ 0, bad currency, missing fields) | `400 VALIDATION_FAILED`, WeChat Pay not called |
| Unknown `provider` | `400 UNKNOWN_PROVIDER` |
| Same `merchantOrderId` twice | `409 DUPLICATE_PAYMENT` |
| WeChat Pay returns an error on create (e.g. `PARAM_ERROR`, `NO_AUTH`) | Payment saved as `FAILED`, `502` with the WeChat Pay `code` |
| WeChat Pay response signature missing or invalid | `502 SECURITY_ERROR` |
| Network error or timeout calling WeChat Pay | `502 NETWORK_ERROR` |
| Notification with a bad signature, a stale timestamp, or the wrong key | `400 INVALID_NOTIFICATION`, payment unchanged |
| Notification amount differs from the payment | `422 AMOUNT_MISMATCH`, logged as an error, payment unchanged |

Every row is covered by a test in `PaymentServiceTest`, `WeChatPayAdapterTest`, or
`PaymentFlowIntegrationTest`.

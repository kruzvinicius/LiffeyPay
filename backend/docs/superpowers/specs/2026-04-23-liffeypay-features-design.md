# LiffeyPay — Features Design Spec
**Date:** 2026-04-23
**Status:** Approved

## Overview

Four features to be implemented in sequence, all related to the transfer flow:

1. MERCHANT transfer restriction
2. Transaction history endpoint
3. External authorization service
4. Async notifications

---

## Feature 1 — MERCHANT Transfer Restriction

### Rule
A wallet owned by a `MERCHANT` user cannot be the **source** of a transfer. Merchants can only receive.

### Changes
- **`MerchantTransferNotAllowedException`** — new exception extending `BusinessException`, HTTP 422.
- **`TransferService.executeTransfer()`** — after acquiring the source wallet lock, check `source.getUser().getUserType() == MERCHANT`. If true, throw `MerchantTransferNotAllowedException`.
- **`GlobalExceptionHandler`** — no change needed; already handles `BusinessException` generically.

### Tests
- `TransferServiceTest`: one new case — transfer with MERCHANT source wallet must throw `MerchantTransferNotAllowedException`.

---

## Feature 2 — Transaction History

### Endpoint
```
GET /api/v1/wallets/{id}/transactions?page=0&size=20
```

### Authorization
Only the wallet owner (authenticated user) can query. Non-owners receive 404 (avoids leaking wallet existence).

### Response
Paginated list of `TransactionResponse`:

| Field               | Type      | Description                        |
|---------------------|-----------|------------------------------------|
| `id`                | UUID      | Transaction ID                     |
| `type`              | String    | `SENT` or `RECEIVED`               |
| `counterpartWalletId` | UUID    | The other wallet in the transfer   |
| `amount`            | BigDecimal | Transfer amount (precision 19,4)  |
| `currency`          | String    | 3-letter currency code             |
| `status`            | String    | `COMPLETED`                        |
| `createdAt`         | Instant   | Timestamp                          |

### Changes
- **`TransactionRepository`** — new method `findAllBySourceWalletIdOrTargetWalletId(UUID, UUID, Pageable)` (Spring Data auto-generated query).
- **`TransactionResponse`** — new DTO record.
- **`TransactionService`** — new service. Method `getTransactions(UUID walletId, String requestingEmail, Pageable)`: validates ownership via `WalletRepository`, queries transactions, maps to `TransactionResponse` (sets `type` based on whether `sourceWalletId == walletId`).
- **`WalletController`** — new handler `GET /{id}/transactions`.

### Tests
- `@DataJpaTest` on `TransactionRepository`: verify pagination and both directions (sent + received).
- `WalletControllerTest`: 2 new cases — owner gets 200 with data; non-owner gets 404.

---

## Feature 3 — External Authorization Service

### Flow
Before acquiring any pessimistic lock, `TransferService` calls the external authorizer. On non-authorized response, timeout, or I/O error → fail-closed → throw `TransferNotAuthorizedException` (HTTP 422). Only then proceed with locks and balance mutation.

### Authorizer Contract
```
GET <app.authorization.url>
Response: { "status": "AUTHORIZED" | "DENIED" }
```

### Changes
- **`AuthorizationService`** — interface: `void authorize(TransferRequest)` (throws on failure).
- **`HttpAuthorizationService`** — implements `AuthorizationService` using `RestClient`. Any non-200 response, `"DENIED"` status, timeout, or I/O exception → fail-closed.
- **`TransferNotAuthorizedException`** — new exception extending `BusinessException`, HTTP 422.
- **`TransferService`** — injects `AuthorizationService`, calls `authorize(request)` at the top of `transfer()`, before `executeTransfer()`.
- **`application.properties`** — `app.authorization.url` and `app.authorization.timeout-ms`.

### Why an interface?
Allows Mockito mocking in unit tests without spinning up HTTP infrastructure.

### Tests
- `TransferServiceTest`: 3 new cases — authorized (proceeds), denied (throws), timeout/I/O failure (throws).

---

## Feature 4 — Async Notifications

### Flow
After the transaction commits, `TransferService` publishes a `TransferCompletedEvent`. A listener picks it up asynchronously and calls the notification service. Failures are silent. `TransferService` has no direct knowledge of notifications.

### Event
```java
record TransferCompletedEvent(
    UUID transactionId,
    UUID sourceWalletId,
    UUID targetWalletId,
    BigDecimal amount,
    String currency
) {}
```

### Changes
- **`TransferCompletedEvent`** — new record in the `service` package (same package as `TransferService`, which publishes it).
- **`TransferService`** — after saving `Transaction`, publishes `TransferCompletedEvent` via `ApplicationEventPublisher`.
- **`NotificationService`** — interface: `void notify(TransferCompletedEvent)`.
- **`HttpNotificationService`** — implements `NotificationService` using `RestClient`. Catches all exceptions, logs warning, does not propagate.
- **`NotificationListener`** — `@Component`. Method annotated with `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`. Calls `notificationService.notify(event)`.
- **`LiffeyPayApplication`** (or a config class) — add `@EnableAsync`.
- **`application.properties`** — `app.notification.url`.

### Why `AFTER_COMMIT`?
Guarantees the notification is only sent if the database transaction actually committed — no risk of notifying a rolled-back transfer.

### Tests
- `NotificationListener`: mock `NotificationService`, verify `notify()` is called.
- `HttpNotificationService`: mock `RestClient`, verify failures are swallowed silently.

---

## Implementation Order

```
Feature 1 → Feature 2 → Feature 3 → Feature 4
```

Each feature is independently testable and does not depend on the next. Features 3 and 4 both extend `TransferService` but at different insertion points (before locks vs after commit), so they compose cleanly.
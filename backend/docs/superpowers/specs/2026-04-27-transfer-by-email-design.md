# LiffeyPay — Transfer by Email Design

**Date:** 2026-04-27
**Feature:** `POST /api/v1/transfers/email` — transfer funds to a recipient identified by email instead of wallet UUID.

---

## Problem

The existing `POST /api/v1/transfers` endpoint requires the caller to know the target's `walletId` (a UUID). This is impractical for real users. Email-based transfer mirrors how Pix, PayPal, and Wise work.

---

## API Contract

```
POST /api/v1/transfers/email
Authorization: Bearer <jwt>
Idempotency-Key: <optional>

Request body:
{
  "recipientEmail": "bob@example.com",
  "amount": 50.00
}

201 Created:
{
  "success": true,
  "data": {
    "transactionId": "uuid",
    "sourceWalletId": "uuid",
    "targetWalletId": "uuid",
    "amount": 50.0000,
    "currency": "EUR",
    "status": "COMPLETED",
    "createdAt": "2026-04-27T10:00:00Z"
  }
}
```

**Error responses:**

| Scenario | Status |
|---|---|
| Recipient email not found | 404 |
| Self-transfer (sender == recipient) | 422 |
| Insufficient funds | 422 |
| MERCHANT source wallet | 422 |
| Unauthenticated | 401 |

Response reuses the existing `TransferResponse` record and `ApiResponse<T>` envelope.

---

## Architecture

### New files

- `dto/TransferByEmailRequest.java` — record with `recipientEmail` (`@Email`, `@NotBlank`) and `amount` (`@NotNull`, `@DecimalMin("0.01")`, `@Digits`)
- `exception/SelfTransferException.java` — extends `BusinessException`, maps to HTTP 422

### Modified files

**`TransferService`** — new public method:

```java
public TransferResponse transferByEmail(
    String senderEmail, String recipientEmail, BigDecimal amount, String idempotencyKey)
```

Flow:
1. Idempotency check (same as existing `transfer()`)
2. Resolve `senderEmail` → `sourceWalletId` via `findByUserEmail` (read-only lookup, no lock yet)
3. Resolve `recipientEmail` → `targetWalletId` via `findByUserEmail`
4. Validate `sourceWalletId != targetWalletId` → throw `SelfTransferException` if equal
5. Build a `TransferRequest(sourceWalletId, targetWalletId, amount)` and delegate to `executeTransfer()`, which acquires pessimistic locks in UUID order (anti-deadlock guarantee preserved)

The email lookups use `findByUserEmail` (no lock). Locks are acquired inside `executeTransfer()` in consistent UUID order — no double-lock risk.

**`TransferController`** — new endpoint:

```java
@PostMapping("/email")
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<TransferResponse> transferByEmail(
    @RequestBody @Valid TransferByEmailRequest request,
    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
    @AuthenticationPrincipal Jwt jwt)
```

JWT subject (`jwt.getSubject()`) is passed as `senderEmail`. `sourceWalletId` is never exposed in the request body.

**`GlobalExceptionHandler`** — new handler for `SelfTransferException` → 422.

---

## Locking Strategy

`executeTransfer()` already acquires `findByIdWithLock` on both wallets in UUID order. `transferByEmail()` resolves emails to IDs first (read-only), then passes those IDs to `executeTransfer()`. No change to the deadlock-prevention logic.

---

## Testing

**`TransferServiceTest`** — 4 new unit tests (Mockito, no Spring context):
- `transferByEmail_happyPath_delegatesToExecuteTransfer` — balance debited, transaction saved
- `transferByEmail_selfTransfer_throwsSelfTransferException`
- `transferByEmail_recipientNotFound_throwsResourceNotFoundException`
- `transferByEmail_senderNotFound_throwsResourceNotFoundException`

**`TransferControllerTest`** — 2 new tests (`@WebMvcTest` + MockMvc):
- `transferByEmail_authenticated_returns201`
- `transferByEmail_unauthenticated_returns401`

**`TransferIT`** — 2 new integration tests (Testcontainers + real DB):
- `transferByEmail_happyPath_balancesUpdatedInDb`
- `transferByEmail_selfTransfer_returns422`

Total: **8 new tests**, TDD order (test → fail → implement → pass).

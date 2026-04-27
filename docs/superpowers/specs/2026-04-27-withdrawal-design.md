# Withdrawal — Design Spec

**Date:** 2026-04-27
**Status:** Approved
**Feature:** `POST /api/v1/wallets/me/withdraw`

---

## Overview

Adds withdrawal (saque) support to the LiffeyPay wallet. Completes the financial cycle: deposit → transfer → **withdraw**. Follows the established `DepositService` pattern throughout.

---

## Data Model

### `TransactionType` enum
Add `WITHDRAWAL` alongside `DEPOSIT` and `TRANSFER`. No entity changes required — `Transaction` already supports the new type via enum.

### DTOs

**`WithdrawalRequest`** (Java record):
```java
BigDecimal amount  // @NotNull, @DecimalMin("0.01")
```

**`WithdrawalResponse`** (Java record):
```java
UUID          transactionId
UUID          walletId
BigDecimal    amount
String        currency
LocalDateTime createdAt
```

---

## Endpoint

```
POST /api/v1/wallets/me/withdraw
Authorization: Bearer <JWT>
Idempotency-Key: <optional UUID>
Content-Type: application/json

{ "amount": 50.00 }

→ 201 Created
{ "success": true, "data": { ... WithdrawalResponse ... } }
```

Added to the existing `WalletController` alongside `/me/deposit`. No new controller.

---

## Service: `WithdrawalService`

Single public method: `withdraw(ownerEmail, amount, idempotencyKey)`

**Execution flow:**

1. **Idempotency check** — if `idempotencyKey` is non-null, query `transactionRepository.findByIdempotencyKey(...)`. On hit, return cached `WithdrawalResponse` without re-executing.
2. **Pessimistic lock** — `walletRepository.findByUserEmailWithLock(ownerEmail)`. Throws `ResourceNotFoundException` if wallet not found.
3. **External authorization** — call `authorizationService.authorize()`. On denial, throw `TransferNotAuthorizedException`.
4. **Balance check** — if `wallet.getBalance() < amount`, throw `InsufficientFundsException`.
5. **Debit** — `wallet.setBalance(balance.subtract(amount))`.
6. **Persist** — save `Transaction` with `type=WITHDRAWAL`, `sourceWallet=wallet`, `status=COMPLETED`, `idempotencyKey` (may be null).

**RBAC:** any authenticated user (COMMON or MERCHANT) may withdraw. Authorization is implicit via JWT filter. No per-role restriction applies — unlike transfers where merchants cannot be payers.

---

## Error Handling

All cases handled by the existing `GlobalExceptionHandler` — no new exceptions needed.

| Scenario | Exception | HTTP Status |
|---|---|---|
| Wallet not found | `ResourceNotFoundException` | 404 |
| Insufficient balance | `InsufficientFundsException` | 422 |
| External auth denied | `TransferNotAuthorizedException` | 403 |
| Idempotency key race (DB unique constraint) | `DataIntegrityViolationException` | 409 |
| Amount null or ≤ 0 | `MethodArgumentNotValidException` | 400 |

---

## Testing

### `WithdrawalServiceTest` (`@ExtendWith(MockitoExtension.class)`)
- Successful withdrawal debits balance and saves Transaction with correct fields
- Idempotency: second call with same key returns cached result without re-executing
- Insufficient balance throws `InsufficientFundsException`
- External auth denial throws `TransferNotAuthorizedException`

### `WithdrawalControllerTest` (`@WebMvcTest`)
- `POST /me/withdraw` returns 201 with correct `WithdrawalResponse` body
- Null amount returns 400
- Zero or negative amount returns 400

### `WithdrawalIntegrationTest` (`@SpringBootTest`)
- Full flow: deposit → withdraw → assert final balance
- Withdraw more than available balance returns 422

---

## Files to Create / Modify

| Action | File |
|---|---|
| Modify | `domain/model/TransactionType.java` — add `WITHDRAWAL` |
| Create | `dto/WithdrawalRequest.java` |
| Create | `dto/WithdrawalResponse.java` |
| Create | `service/WithdrawalService.java` |
| Modify | `controller/WalletController.java` — add endpoint + inject service |
| Create | `WithdrawalServiceTest.java` |
| Create | `WithdrawalControllerTest.java` |
| Create | `WithdrawalIntegrationTest.java` |

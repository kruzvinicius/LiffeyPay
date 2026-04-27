# LiffeyPay — Deposit Endpoint Design Spec
**Date:** 2026-04-24
**Status:** Approved

## Overview

Add `POST /api/v1/wallets/me/deposit` to allow authenticated users to top-up their own wallet. Deposits are recorded as `Transaction` entries with `type = DEPOSIT` and appear in the transaction history endpoint. Supports idempotency via `Idempotency-Key` header.

---

## Data Model Changes

### Flyway V4 Migration

Three changes in a single migration:

1. Add `type` column to `transactions` with default `'TRANSFER'` for existing rows
2. Make `source_wallet_id` nullable (deposits have no source)
3. Drop the column default after backfill (enforced at application level)

```sql
ALTER TABLE transactions ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'TRANSFER';
ALTER TABLE transactions ALTER COLUMN source_wallet_id DROP NOT NULL;
ALTER TABLE transactions ALTER COLUMN type DROP DEFAULT;
```

### `TransactionType` Enum

New file: `domain/model/TransactionType.java`

```java
public enum TransactionType { TRANSFER, DEPOSIT }
```

### `Transaction` Entity Changes

- Add `@Enumerated(EnumType.STRING) @Column(nullable = false) TransactionType type` with `@Builder.Default` of `TRANSFER`
- Remove `nullable = false` from `sourceWallet` `@JoinColumn`

### `TransactionResponse` Changes

No signature change. For deposits: `type = "DEPOSIT"`, `counterpartWalletId = null`.

### `TransactionService.toResponse()` Changes

Add branch before the existing SENT/RECEIVED logic:

```java
if (t.getType() == TransactionType.DEPOSIT) {
    return new TransactionResponse(t.getId(), "DEPOSIT", null,
        t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt());
}
```

---

## API Contract

### Request

```
POST /api/v1/wallets/me/deposit
Authorization: Bearer <jwt>
Idempotency-Key: <string>   (optional)
Content-Type: application/json

{ "amount": 100.00 }
```

### `DepositRequest` DTO

```java
public record DepositRequest(
    @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4)
    BigDecimal amount
) {}
```

### Response

**201 Created:**
```json
{
  "success": true,
  "data": {
    "id": "<uuid>",
    "walletId": "<uuid>",
    "amount": "100.0000",
    "currency": "EUR",
    "createdAt": "<iso-instant>"
  }
}
```

### `DepositResponse` DTO

```java
public record DepositResponse(
    UUID id,
    UUID walletId,
    BigDecimal amount,
    String currency,
    Instant createdAt
) {}
```

### Error Cases

| Scenario | HTTP |
|---|---|
| No JWT | 401 |
| `amount < 0.01` | 400 (bean validation) |
| Duplicate `Idempotency-Key` race condition | 409 (existing `DataIntegrityViolationException` handler) |

---

## Service Layer

### `WalletRepository` — new method

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.user.email = :email")
Optional<Wallet> findByUserEmailWithLock(@Param("email") String email);
```

### `DepositService`

Method: `deposit(String ownerEmail, BigDecimal amount, String idempotencyKey) → DepositResponse`

Flow:
1. If `idempotencyKey` non-blank → check `transactionRepository.findByIdempotencyKey(key)` → return cached `DepositResponse` if found
2. `walletRepository.findByUserEmailWithLock(ownerEmail)` — pessimistic write lock
3. If wallet not found → throw `ResourceNotFoundException`
4. `wallet.setBalance(wallet.getBalance().add(amount))`
5. Save `Transaction(type=DEPOSIT, sourceWallet=null, targetWallet=wallet, amount, currency=wallet.currency, status=COMPLETED, idempotencyKey)`
6. Return `DepositResponse`

No external authorization call (deposit is self-initiated, no fraud risk from external service). No notification event (keep scope focused).

### `WalletController` — new endpoint

```java
@PostMapping("/me/deposit")
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<DepositResponse> deposit(
    @Valid @RequestBody DepositRequest request,
    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
    @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(depositService.deposit(jwt.getSubject(), request.amount(), idempotencyKey));
}
```

`DepositService` injected into `WalletController` alongside existing services.

---

## Tests

### `DepositServiceTest` (unit, Mockito)

| Test | Assertion |
|---|---|
| `deposit_happyPath_increasesBalanceAndSavesDeposit` | balance increases, `save()` called with `type=DEPOSIT`, `sourceWallet=null` |
| `deposit_idempotency_cachedResultReturnedWithoutExecuting` | existing key → cached result, `save()` never called |
| `deposit_newIdempotencyKey_executesAndSaves` | new key → executes, `save()` called once |
| `deposit_walletNotFound_throwsResourceNotFoundException` | no wallet for email → exception |

### `DepositIT` (integration, extends `IntegrationTestBase`)

| Test | Assertion |
|---|---|
| `deposit_happyPath_balanceIncreasedInDb` | 201, balance in DB increased by deposit amount |
| `deposit_idempotency_secondCallReturnsCached` | same key twice → same `id`, balance not duplicated |
| `deposit_noToken_returns401` | 401 |
| `deposit_appearsInTransactionHistory` | deposit visible in `GET /wallets/{id}/transactions` with `type=DEPOSIT` |

---

## File Map

- Create: `backend/src/main/resources/db/migration/V4__add_deposit_support.sql`
- Create: `backend/src/main/java/com/liffeypay/liffeypay/domain/model/TransactionType.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/domain/model/Transaction.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/domain/repository/WalletRepository.java`
- Create: `backend/src/main/java/com/liffeypay/liffeypay/dto/DepositRequest.java`
- Create: `backend/src/main/java/com/liffeypay/liffeypay/dto/DepositResponse.java`
- Create: `backend/src/main/java/com/liffeypay/liffeypay/service/DepositService.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/service/TransactionService.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/controller/WalletController.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/service/DepositServiceTest.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/DepositIT.java`

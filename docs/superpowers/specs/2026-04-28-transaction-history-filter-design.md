# Transaction History Filter — Design Spec

**Date:** 2026-04-28  
**Feature:** Filter transaction history by type (`DEPOSIT`, `WITHDRAWAL`, `SENT`, `RECEIVED`)

---

## Context

`GET /api/v1/wallets/{walletId}/transactions` currently returns all transactions for a wallet, paginated, sorted by `createdAt DESC`. The response already distinguishes 4 types (`DEPOSIT`, `WITHDRAWAL`, `SENT`, `RECEIVED`), but there is no way to filter by type. This spec adds an optional `type` query parameter.

---

## API Contract

```
GET /api/v1/wallets/{walletId}/transactions?type=SENT&page=0&size=20
```

- `type` is optional. When absent, all transactions are returned (current behavior preserved).
- Valid values: `DEPOSIT`, `WITHDRAWAL`, `SENT`, `RECEIVED` (case-sensitive).
- Invalid value → `400 Bad Request` via `BusinessException`.

Response shape is unchanged (`ApiResponse<PageResponse<TransactionResponse>>`).

---

## DB Mapping

The `transactions` table stores 3 types (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`). The response types `SENT` and `RECEIVED` are both `TRANSFER` at the DB level, distinguished by which wallet is the source/target:

| API `type` | DB `type` | Column condition |
|---|---|---|
| `DEPOSIT` | `DEPOSIT` | `target_wallet_id = walletId` |
| `WITHDRAWAL` | `WITHDRAWAL` | `source_wallet_id = walletId` |
| `SENT` | `TRANSFER` | `source_wallet_id = walletId` |
| `RECEIVED` | `TRANSFER` | `target_wallet_id = walletId` |
| *(absent)* | any | `source_wallet_id = walletId OR target_wallet_id = walletId` |

---

## Implementation

### `TransactionRepository`

Add 2 Spring Data derived methods:

```java
Page<Transaction> findByTargetWalletIdAndType(UUID targetWalletId, TransactionType type, Pageable pageable);
Page<Transaction> findBySourceWalletIdAndType(UUID sourceWalletId, TransactionType type, Pageable pageable);
```

### `TransactionService`

`getTransactions` gains a `String type` parameter. Validation: if `type` is not null and not one of the 4 valid values, throw `BusinessException` (400).

Dispatch logic:

```
null       → findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable)
"DEPOSIT"  → findByTargetWalletIdAndType(walletId, DEPOSIT, pageable)
"WITHDRAWAL" → findBySourceWalletIdAndType(walletId, WITHDRAWAL, pageable)
"SENT"     → findBySourceWalletIdAndType(walletId, TRANSFER, pageable)
"RECEIVED" → findByTargetWalletIdAndType(walletId, TRANSFER, pageable)
```

### `WalletController`

Add `@RequestParam(required = false) String type` to `getTransactions` and pass it to the service.

---

## Error Handling

Invalid `type` → `BusinessException` (400) with message:  
`"Invalid transaction type filter: XYZ. Valid values: DEPOSIT, WITHDRAWAL, SENT, RECEIVED"`

---

## Tests

### Unit — `TransactionServiceTest`

- `withTypeDeposit` → calls `findByTargetWalletIdAndType` with `DEPOSIT`
- `withTypeWithdrawal` → calls `findBySourceWalletIdAndType` with `WITHDRAWAL`
- `withTypeSent` → calls `findBySourceWalletIdAndType` with `TRANSFER`
- `withTypeReceived` → calls `findByTargetWalletIdAndType` with `TRANSFER`
- `withNullType` → calls existing `findAllBySourceWalletIdOrTargetWalletId`
- `withInvalidType` → throws `BusinessException` (400)

### Integration — `TransactionHistoryIT`

- `filterByDeposit` — deposits + transfer; `?type=DEPOSIT` returns only deposits
- `filterBySent` — transfer; `?type=SENT` returns only the sent transaction
- `filterByReceived` — transfer; `?type=RECEIVED` returns only the received transaction (queried from recipient's wallet)
- `filterByWithdrawal` — withdrawal; `?type=WITHDRAWAL` returns only the withdrawal
- `invalidType_returns400`

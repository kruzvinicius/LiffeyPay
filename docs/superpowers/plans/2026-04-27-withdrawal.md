# Withdrawal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /api/v1/wallets/me/withdraw` so any authenticated user can debit their wallet with idempotency and external authorization.

**Architecture:** `WithdrawalService` mirrors `DepositService` — pessimistic lock, idempotency check, external auth via `AuthorizationService`, balance check, then debit. The `AuthorizationService` interface is refactored first to remove its unused `TransferRequest` parameter so `WithdrawalService` can call it cleanly. `target_wallet_id` is made nullable via migration since withdrawals have no internal target.

**Tech Stack:** Spring Boot 3.4.3 / Java 21, Spring Data JPA, Flyway, Mockito, WireMock, Testcontainers

---

## Task 1: Refactor `AuthorizationService` — remove unused parameter

The `authorize(TransferRequest)` parameter is never read by `HttpAuthorizationService` (it makes a parameterless GET). Removing it now means `WithdrawalService` can call the interface without passing a dummy object.

**Files:**
- Modify: `src/main/java/com/liffeypay/liffeypay/service/AuthorizationService.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/HttpAuthorizationService.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/HttpAuthorizationServiceTest.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`

- [ ] **Step 1: Update `AuthorizationService` interface**

Replace the entire file content:

```java
package com.liffeypay.liffeypay.service;

public interface AuthorizationService {
    void authorize();
}
```

- [ ] **Step 2: Update `HttpAuthorizationService` — remove param from override**

Change the method signature from `public void authorize(TransferRequest request)` to:

```java
@Override
public void authorize() {
    try {
        AuthorizationResponse response = restClient.get()
            .retrieve()
            .body(AuthorizationResponse.class);
        if (response == null || !"AUTHORIZED".equalsIgnoreCase(response.status())) {
            throw new TransferNotAuthorizedException("Transfer denied by authorization service");
        }
    } catch (TransferNotAuthorizedException e) {
        throw e;
    } catch (Exception e) {
        log.warn("Authorization service error: {}", e.getMessage());
        throw new TransferNotAuthorizedException("Authorization service unavailable");
    }
}
```

Also remove the unused `import com.liffeypay.liffeypay.dto.TransferRequest;` from the file.

- [ ] **Step 3: Update `TransferService.executeTransfer()` — remove param from call**

Find the line `authorizationService.authorize(request);` in `executeTransfer()` and change it to:

```java
authorizationService.authorize();
```

- [ ] **Step 4: Update `HttpAuthorizationServiceTest` — remove `TransferRequest` usage**

Replace the entire file content:

```java
package com.liffeypay.liffeypay.service;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAuthorizationServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private HttpAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new HttpAuthorizationService(
            wm.getRuntimeInfo().getHttpBaseUrl() + "/authorize", 3000);
    }

    @Test
    void authorize_authorized_noExceptionThrown() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"AUTHORIZED\"}")));

        assertThatNoException().isThrownBy(() -> service.authorize());
    }

    @Test
    void authorize_denied_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"DENIED\"}")));

        assertThatThrownBy(() -> service.authorize())
            .isInstanceOf(TransferNotAuthorizedException.class);
    }

    @Test
    void authorize_serverError_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.authorize())
            .isInstanceOf(TransferNotAuthorizedException.class);
    }

    @Test
    void authorize_connectionReset_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> service.authorize())
            .isInstanceOf(TransferNotAuthorizedException.class);
    }
}
```

- [ ] **Step 5: Update `TransferServiceTest` — fix `authorize` stubs**

Find both occurrences of `.when(authorizationService).authorize(any())` and change each to `.when(authorizationService).authorize()` (no argument). There are two such lines, in tests `transfer_deniedByAuthorizer_throwsTransferNotAuthorizedException` and `transfer_authorizerUnavailable_throwsTransferNotAuthorizedException`.

- [ ] **Step 6: Run tests to confirm nothing broke**

```bash
./mvnw test
```

Expected: BUILD SUCCESS, same count as before.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/service/AuthorizationService.java \
        src/main/java/com/liffeypay/liffeypay/service/HttpAuthorizationService.java \
        src/main/java/com/liffeypay/liffeypay/service/TransferService.java \
        src/test/java/com/liffeypay/liffeypay/service/HttpAuthorizationServiceTest.java \
        src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java
git commit -m "refactor: remove unused TransferRequest param from AuthorizationService"
```

---

## Task 2: DB migration + entity — make `target_wallet_id` nullable

WITHDRAWAL transactions have no internal target wallet (money exits the system). V3 created `target_wallet_id NOT NULL`; this migration relaxes it so `WithdrawalService` can leave it null.

**Files:**
- Create: `src/main/resources/db/migration/V5__allow_null_target_wallet.sql`
- Modify: `src/main/java/com/liffeypay/liffeypay/domain/model/Transaction.java`

- [ ] **Step 1: Create Flyway migration**

Create file `src/main/resources/db/migration/V5__allow_null_target_wallet.sql` with content:

```sql
ALTER TABLE transactions ALTER COLUMN target_wallet_id DROP NOT NULL;
```

- [ ] **Step 2: Update `Transaction.java` entity — remove `nullable = false`**

Find the `targetWallet` field:
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "target_wallet_id", nullable = false)
private Wallet targetWallet;
```

Change it to:
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "target_wallet_id")
private Wallet targetWallet;
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test
```

Expected: BUILD SUCCESS. Existing deposit/transfer tests are unaffected — migration only relaxes a constraint.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V5__allow_null_target_wallet.sql \
        src/main/java/com/liffeypay/liffeypay/domain/model/Transaction.java
git commit -m "feat: make target_wallet_id nullable to support withdrawal transactions"
```

---

## Task 3: Add `WITHDRAWAL` to `TransactionType` enum + create DTOs

**Files:**
- Modify: `src/main/java/com/liffeypay/liffeypay/domain/model/TransactionType.java`
- Create: `src/main/java/com/liffeypay/liffeypay/dto/WithdrawalRequest.java`
- Create: `src/main/java/com/liffeypay/liffeypay/dto/WithdrawalResponse.java`

- [ ] **Step 1: Add `WITHDRAWAL` to enum**

Replace entire file:
```java
package com.liffeypay.liffeypay.domain.model;

public enum TransactionType {
    TRANSFER,
    DEPOSIT,
    WITHDRAWAL
}
```

- [ ] **Step 2: Create `WithdrawalRequest`**

Create `src/main/java/com/liffeypay/liffeypay/dto/WithdrawalRequest.java`:
```java
package com.liffeypay.liffeypay.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawalRequest(
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "Invalid monetary amount format")
    BigDecimal amount
) {}
```

- [ ] **Step 3: Create `WithdrawalResponse`**

Create `src/main/java/com/liffeypay/liffeypay/dto/WithdrawalResponse.java`:
```java
package com.liffeypay.liffeypay.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalResponse(
    UUID id,
    UUID walletId,
    BigDecimal amount,
    String currency,
    Instant createdAt
) {}
```

- [ ] **Step 4: Build to verify no compile errors**

```bash
./mvnw compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/domain/model/TransactionType.java \
        src/main/java/com/liffeypay/liffeypay/dto/WithdrawalRequest.java \
        src/main/java/com/liffeypay/liffeypay/dto/WithdrawalResponse.java
git commit -m "feat: add WITHDRAWAL transaction type and DTOs"
```

---

## Task 4: Update `TransactionService` to handle WITHDRAWAL in history

Without this change, a WITHDRAWAL transaction would fall into the TRANSFER branch of `toResponse()` and crash on `t.getTargetWallet().getId()` (NPE since targetWallet is null).

**Files:**
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransactionService.java`

- [ ] **Step 1: Write failing test**

Add this test to `TransactionServiceTest` after the `getTransactions_deposit_returnsDepositTypeWithNullCounterpart` test:

```java
@Test
void getTransactions_withdrawal_returnsWithdrawalTypeWithNullCounterpart() {
    Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
    Transaction withdrawal = Transaction.builder()
        .id(UUID.randomUUID()).sourceWallet(wallet)
        .amount(new BigDecimal("25.0000")).currency("EUR")
        .type(TransactionType.WITHDRAWAL)
        .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

    PageRequest pageable = PageRequest.of(0, 20);
    when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
    when(transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable))
        .thenReturn(new PageImpl<>(List.of(withdrawal)));

    Page<TransactionResponse> result =
        transactionService.getTransactions(walletId, OWNER_EMAIL, pageable);

    assertThat(result.getContent().get(0).type()).isEqualTo("WITHDRAWAL");
    assertThat(result.getContent().get(0).counterpartWalletId()).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=TransactionServiceTest#getTransactions_withdrawal_returnsWithdrawalTypeWithNullCounterpart
```

Expected: FAIL — the test attempts to call `t.getTargetWallet().getId()` on a null targetWallet.

- [ ] **Step 3: Update `toResponse()` in `TransactionService`**

Find the `toResponse` private method in `TransactionService.java`:

```java
private TransactionResponse toResponse(Transaction t, UUID walletId) {
    if (t.getType() == TransactionType.DEPOSIT) {
        return new TransactionResponse(t.getId(), "DEPOSIT", null,
            t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt());
    }
    boolean sent = t.getSourceWallet().getId().equals(walletId);
    UUID counterpart = sent ? t.getTargetWallet().getId() : t.getSourceWallet().getId();
    return new TransactionResponse(
        t.getId(), sent ? "SENT" : "RECEIVED", counterpart,
        t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt()
    );
}
```

Replace it with:

```java
private TransactionResponse toResponse(Transaction t, UUID walletId) {
    if (t.getType() == TransactionType.DEPOSIT) {
        return new TransactionResponse(t.getId(), "DEPOSIT", null,
            t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt());
    }
    if (t.getType() == TransactionType.WITHDRAWAL) {
        return new TransactionResponse(t.getId(), "WITHDRAWAL", null,
            t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt());
    }
    boolean sent = t.getSourceWallet().getId().equals(walletId);
    UUID counterpart = sent ? t.getTargetWallet().getId() : t.getSourceWallet().getId();
    return new TransactionResponse(
        t.getId(), sent ? "SENT" : "RECEIVED", counterpart,
        t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt()
    );
}
```

- [ ] **Step 4: Run all `TransactionServiceTest` tests**

```bash
./mvnw test -Dtest=TransactionServiceTest
```

Expected: all tests pass including the new one.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/service/TransactionService.java \
        src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java
git commit -m "feat: handle WITHDRAWAL type in transaction history response"
```

---

## Task 5: Create `WithdrawalService` (TDD)

**Files:**
- Create: `src/test/java/com/liffeypay/liffeypay/service/WithdrawalServiceTest.java` (write first)
- Create: `src/main/java/com/liffeypay/liffeypay/service/WithdrawalService.java`

- [ ] **Step 1: Write the full test class**

Create `src/test/java/com/liffeypay/liffeypay/service/WithdrawalServiceTest.java`:

```java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.WithdrawalResponse;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AuthorizationService authorizationService;
    @InjectMocks WithdrawalService withdrawalService;

    private static final String OWNER_EMAIL = "owner@test.com";
    private static final UUID WALLET_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        User user = User.builder()
            .id(UUID.randomUUID()).fullName("Owner").email(OWNER_EMAIL)
            .documentNumber("11122233344").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build();
        wallet = Wallet.builder()
            .id(WALLET_ID).user(user)
            .balance(new BigDecimal("100.0000")).currency("EUR").build();
    }

    private Transaction savedWithdrawal(BigDecimal amount) {
        return Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(wallet)
            .amount(amount).currency("EUR")
            .type(TransactionType.WITHDRAWAL).status(TransactionStatus.COMPLETED)
            .createdAt(Instant.now()).build();
    }

    @Test
    void withdraw_happyPath_decreasesBalanceAndSavesWithdrawal() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any())).thenReturn(savedWithdrawal(new BigDecimal("30.0000")));

        WithdrawalResponse response = withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), null);

        assertThat(wallet.getBalance()).isEqualByComparingTo("70.0000");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction tx = captor.getValue();
        assertThat(tx.getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(tx.getSourceWallet()).isEqualTo(wallet);
        assertThat(tx.getTargetWallet()).isNull();

        assertThat(response.walletId()).isEqualTo(WALLET_ID);
    }

    @Test
    void withdraw_idempotency_cachedResultReturnedWithoutExecuting() {
        String key = "withdraw-key-abc";
        Transaction cached = savedWithdrawal(new BigDecimal("30.0000"));
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(cached));

        WithdrawalResponse response = withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), key);

        assertThat(response.id()).isEqualTo(cached.getId());
        verify(walletRepository, never()).findByUserEmailWithLock(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void withdraw_insufficientFunds_throwsInsufficientFundsException() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("200.00"), null))
            .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void withdraw_authorizationDenied_throwsTransferNotAuthorizedException() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        doThrow(new TransferNotAuthorizedException("Denied"))
            .when(authorizationService).authorize();

        assertThatThrownBy(() -> withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), null))
            .isInstanceOf(TransferNotAuthorizedException.class);

        verify(transactionRepository, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (class doesn't exist yet)**

```bash
./mvnw test -Dtest=WithdrawalServiceTest
```

Expected: FAIL — compilation error, `WithdrawalService` not found.

- [ ] **Step 3: Implement `WithdrawalService`**

Create `src/main/java/com/liffeypay/liffeypay/service/WithdrawalService.java`:

```java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionStatus;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.WithdrawalResponse;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional
public class WithdrawalService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final AuthorizationService authorizationService;

    public WithdrawalResponse withdraw(String ownerEmail, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> executeWithdrawal(ownerEmail, amount, idempotencyKey));
        }
        return executeWithdrawal(ownerEmail, amount, null);
    }

    private WithdrawalResponse executeWithdrawal(String ownerEmail, BigDecimal amount, String idempotencyKey) {
        Wallet wallet = walletRepository.findByUserEmailWithLock(ownerEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for: " + ownerEmail));

        authorizationService.authorize();

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(wallet.getId(), wallet.getBalance(), amount);
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));

        Transaction saved = transactionRepository.save(Transaction.builder()
            .type(TransactionType.WITHDRAWAL)
            .sourceWallet(wallet)
            .amount(amount)
            .currency(wallet.getCurrency())
            .status(TransactionStatus.COMPLETED)
            .idempotencyKey(idempotencyKey)
            .build());

        return toResponse(saved);
    }

    private WithdrawalResponse toResponse(Transaction t) {
        return new WithdrawalResponse(
            t.getId(),
            t.getSourceWallet().getId(),
            t.getAmount(),
            t.getCurrency(),
            t.getCreatedAt()
        );
    }
}
```

- [ ] **Step 4: Run all `WithdrawalServiceTest` tests**

```bash
./mvnw test -Dtest=WithdrawalServiceTest
```

Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/service/WithdrawalService.java \
        src/test/java/com/liffeypay/liffeypay/service/WithdrawalServiceTest.java
git commit -m "feat: implement WithdrawalService with idempotency and external authorization"
```

---

## Task 6: Wire endpoint in `WalletController` + controller tests

**Files:**
- Modify: `src/test/java/com/liffeypay/liffeypay/controller/WalletControllerTest.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/controller/WalletController.java`

- [ ] **Step 1: Add `WithdrawalService` mock and three test methods to `WalletControllerTest`**

In `WalletControllerTest.java`:

1. Add `@MockBean WithdrawalService withdrawalService;` alongside the other `@MockBean` declarations.

2. Add the import for `WithdrawalService` and `WithdrawalResponse`:
```java
import com.liffeypay.liffeypay.dto.WithdrawalResponse;
import com.liffeypay.liffeypay.service.WithdrawalService;
```

3. Add these three test methods at the end of the class:

```java
@Test
void withdraw_authenticated_returns201() throws Exception {
    WithdrawalResponse withdrawalResponse = new WithdrawalResponse(
        UUID.randomUUID(), WALLET_ID, new BigDecimal("50.0000"), "EUR", Instant.now());
    when(withdrawalService.withdraw(eq(OWNER_EMAIL), any(), any())).thenReturn(withdrawalResponse);

    mockMvc.perform(post("/api/v1/wallets/me/withdraw")
            .with(jwt().jwt(j -> j.subject(OWNER_EMAIL)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\": 50.00}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.walletId").value(WALLET_ID.toString()));
}

@Test
void withdraw_invalidAmount_returns400() throws Exception {
    mockMvc.perform(post("/api/v1/wallets/me/withdraw")
            .with(jwt().jwt(j -> j.subject(OWNER_EMAIL)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\": 0}"))
        .andExpect(status().isBadRequest());
}

@Test
void withdraw_unauthenticated_returns401() throws Exception {
    mockMvc.perform(post("/api/v1/wallets/me/withdraw")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\": 50.00}"))
        .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: Run controller tests to confirm they fail**

```bash
./mvnw test -Dtest=WalletControllerTest
```

Expected: new tests fail — endpoint doesn't exist yet (404).

- [ ] **Step 3: Update `WalletController` — add `WithdrawalService` and endpoint**

In `WalletController.java`:

1. Add `WithdrawalService` to the field list (Lombok `@RequiredArgsConstructor` handles constructor injection automatically):
```java
private final WithdrawalService withdrawalService;
```

2. Add the following imports at the top of the file:
```java
import com.liffeypay.liffeypay.dto.WithdrawalRequest;
import com.liffeypay.liffeypay.dto.WithdrawalResponse;
import com.liffeypay.liffeypay.service.WithdrawalService;
```

3. Add the endpoint method after the `deposit` method:
```java
@PostMapping("/me/withdraw")
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<WithdrawalResponse> withdraw(
        @Valid @RequestBody WithdrawalRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(withdrawalService.withdraw(jwt.getSubject(), request.amount(), idempotencyKey));
}
```

- [ ] **Step 4: Run all `WalletControllerTest` tests**

```bash
./mvnw test -Dtest=WalletControllerTest
```

Expected: all tests pass including the three new ones.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/controller/WalletController.java \
        src/test/java/com/liffeypay/liffeypay/controller/WalletControllerTest.java
git commit -m "feat: add POST /wallets/me/withdraw endpoint"
```

---

## Task 7: Integration tests (`WithdrawalIT`)

**Files:**
- Create: `src/test/java/com/liffeypay/liffeypay/integration/WithdrawalIT.java`

- [ ] **Step 1: Create `WithdrawalIT`**

Create `src/test/java/com/liffeypay/liffeypay/integration/WithdrawalIT.java`:

```java
package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.dto.DepositRequest;
import com.liffeypay.liffeypay.dto.WithdrawalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalIT extends IntegrationTestBase {

    private static final String AUTHORIZED_BODY = "{\"status\":\"AUTHORIZED\"}";

    @Test
    @SuppressWarnings("unchecked")
    void withdraw_happyPath_balanceDecreasedInDb() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String jwt = registerAndLogin("wd1@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), jwt), Map.class);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST,
            withAuth(new WithdrawalRequest(new BigDecimal("40.00")), jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isEqualTo(walletId.toString());

        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        assertThat(balance).isEqualByComparingTo("60.0000");
    }

    @Test
    void withdraw_insufficientFunds_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String jwt = registerAndLogin("wd2@test.com", "password123");

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST,
            withAuth(new WithdrawalRequest(new BigDecimal("50.00")), jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withdraw_idempotency_secondCallReturnsCached() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String jwt = registerAndLogin("wd3@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), jwt), Map.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.set("Idempotency-Key", "withdraw-idem-key-001");
        HttpEntity<WithdrawalRequest> entity =
            new HttpEntity<>(new WithdrawalRequest(new BigDecimal("30.00")), headers);

        ResponseEntity<Map> first  = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST, entity, Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST, entity, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> firstData  = (Map<String, Object>) ((Map<String, Object>) first.getBody()).get("data");
        Map<String, Object> secondData = (Map<String, Object>) ((Map<String, Object>) second.getBody()).get("data");
        assertThat(firstData.get("id")).isEqualTo(secondData.get("id"));

        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        assertThat(balance).isEqualByComparingTo("70.0000");
    }

    @Test
    void withdraw_authorizerDenies_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"DENIED\"}")));

        String jwt = registerAndLogin("wd4@test.com", "password123");
        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), jwt), Map.class);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST,
            withAuth(new WithdrawalRequest(new BigDecimal("50.00")), jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void withdraw_noToken_returns401() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/wallets/me/withdraw",
            new WithdrawalRequest(new BigDecimal("50.00")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run integration tests**

```bash
./mvnw test -Dtest=WithdrawalIT
```

Expected: all 5 tests pass.

- [ ] **Step 3: Run full test suite to check for regressions**

```bash
./mvnw test
```

Expected: BUILD SUCCESS — all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/liffeypay/liffeypay/integration/WithdrawalIT.java
git commit -m "test: add WithdrawalIT integration tests"
```

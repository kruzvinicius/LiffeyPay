# Transaction History Type Filter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional `?type=DEPOSIT|WITHDRAWAL|SENT|RECEIVED` query parameter to `GET /api/v1/wallets/{walletId}/transactions`.

**Architecture:** Two new Spring Data derived methods on `TransactionRepository` handle the filtered queries. `TransactionService` validates the type string and dispatches to the correct method. `WalletController` passes the type param through unchanged. Response shape is unchanged.

**Tech Stack:** Spring Boot 3.4.3, Java 21, Spring Data JPA, JUnit 5 + Mockito (unit), Testcontainers + WireMock (integration).

---

## File Map

| File | Change |
|---|---|
| `backend/src/main/java/.../domain/repository/TransactionRepository.java` | Add 2 derived query methods |
| `backend/src/main/java/.../service/TransactionService.java` | Add `type` param, validation, dispatch |
| `backend/src/main/java/.../controller/WalletController.java` | Add `type` query param, pass to service |
| `backend/src/test/java/.../service/TransactionServiceTest.java` | Update 3 existing calls + add 6 new tests |
| `backend/src/test/java/.../controller/WalletControllerTest.java` | Update 2 existing mocks + add 1 new test |
| `backend/src/test/java/.../integration/TransactionHistoryIT.java` | Add 5 integration tests |

---

## Task 1: Add repository query methods

**Files:**
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/domain/repository/TransactionRepository.java`

- [ ] **Step 1: Add 2 derived methods to `TransactionRepository`**

Replace the current content of `TransactionRepository.java` with:

```java
package com.liffeypay.liffeypay.domain.repository;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    Page<Transaction> findAllBySourceWalletIdOrTargetWalletId(UUID sourceWalletId, UUID targetWalletId, Pageable pageable);
    Page<Transaction> findByTargetWalletIdAndType(UUID targetWalletId, TransactionType type, Pageable pageable);
    Page<Transaction> findBySourceWalletIdAndType(UUID sourceWalletId, TransactionType type, Pageable pageable);
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd backend && ./mvnw compile -q
```

Expected: `BUILD SUCCESS` with no output.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/liffeypay/liffeypay/domain/repository/TransactionRepository.java
git commit -m "feat: add findByTargetWalletIdAndType and findBySourceWalletIdAndType to TransactionRepository"
```

---

## Task 2: Service — type validation and dispatch (TDD)

**Files:**
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/service/TransactionService.java`

- [ ] **Step 1: Update existing test calls and add 6 new tests in `TransactionServiceTest`**

The existing tests call `getTransactions(walletId, email, pageable)`. The method is gaining a `type` parameter between `email` and `pageable` — update all 5 existing calls and add 6 new tests.

Replace the full content of `TransactionServiceTest.java`:

```java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.exception.BusinessException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks TransactionService transactionService;

    private final UUID walletId = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
    private final UUID otherId  = UUID.fromString("bbbb0000-0000-0000-0000-000000000002");
    private static final String OWNER_EMAIL = "owner@test.com";

    // ── existing tests (type = null → unfiltered) ──────────────────────────

    @Test
    void getTransactions_owner_returnsMixedPageWithCorrectTypes() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Wallet other  = Wallet.builder().id(otherId).balance(BigDecimal.ZERO).currency("EUR").build();

        Transaction sent = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(wallet).targetWallet(other)
            .amount(new BigDecimal("50.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();
        Transaction received = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(other).targetWallet(wallet)
            .amount(new BigDecimal("30.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable))
            .thenReturn(new PageImpl<>(List.of(sent, received)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, null, pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).type()).isEqualTo("SENT");
        assertThat(result.getContent().get(0).counterpartWalletId()).isEqualTo(otherId);
        assertThat(result.getContent().get(1).type()).isEqualTo("RECEIVED");
        assertThat(result.getContent().get(1).counterpartWalletId()).isEqualTo(otherId);
    }

    @Test
    void getTransactions_nonOwner_throwsResourceNotFoundException() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() ->
            transactionService.getTransactions(walletId, "hacker@evil.com", null, PageRequest.of(0, 20)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getTransactions_walletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findById(walletId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            transactionService.getTransactions(walletId, OWNER_EMAIL, null, PageRequest.of(0, 20)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getTransactions_deposit_returnsDepositTypeWithNullCounterpart() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Transaction deposit = Transaction.builder()
            .id(UUID.randomUUID()).targetWallet(wallet)
            .amount(new BigDecimal("100.0000")).currency("EUR")
            .type(TransactionType.DEPOSIT)
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable))
            .thenReturn(new PageImpl<>(List.of(deposit)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, null, pageable);

        assertThat(result.getContent().get(0).type()).isEqualTo("DEPOSIT");
        assertThat(result.getContent().get(0).counterpartWalletId()).isNull();
    }

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
            transactionService.getTransactions(walletId, OWNER_EMAIL, null, pageable);

        assertThat(result.getContent().get(0).type()).isEqualTo("WITHDRAWAL");
        assertThat(result.getContent().get(0).counterpartWalletId()).isNull();
    }

    // ── new tests: type filter dispatch ────────────────────────────────────

    @Test
    void getTransactions_withTypeDeposit_callsTargetWalletFilter() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Transaction deposit = Transaction.builder()
            .id(UUID.randomUUID()).targetWallet(wallet)
            .amount(new BigDecimal("100.0000")).currency("EUR")
            .type(TransactionType.DEPOSIT)
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByTargetWalletIdAndType(walletId, TransactionType.DEPOSIT, pageable))
            .thenReturn(new PageImpl<>(List.of(deposit)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, "DEPOSIT", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).type()).isEqualTo("DEPOSIT");
    }

    @Test
    void getTransactions_withTypeWithdrawal_callsSourceWalletFilter() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Transaction withdrawal = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(wallet)
            .amount(new BigDecimal("25.0000")).currency("EUR")
            .type(TransactionType.WITHDRAWAL)
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findBySourceWalletIdAndType(walletId, TransactionType.WITHDRAWAL, pageable))
            .thenReturn(new PageImpl<>(List.of(withdrawal)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, "WITHDRAWAL", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).type()).isEqualTo("WITHDRAWAL");
    }

    @Test
    void getTransactions_withTypeSent_callsSourceWalletFilterWithTransfer() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Wallet other = Wallet.builder().id(otherId).balance(BigDecimal.ZERO).currency("EUR").build();
        Transaction sent = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(wallet).targetWallet(other)
            .amount(new BigDecimal("50.0000")).currency("EUR")
            .type(TransactionType.TRANSFER)
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findBySourceWalletIdAndType(walletId, TransactionType.TRANSFER, pageable))
            .thenReturn(new PageImpl<>(List.of(sent)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, "SENT", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).type()).isEqualTo("SENT");
    }

    @Test
    void getTransactions_withTypeReceived_callsTargetWalletFilterWithTransfer() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Wallet other = Wallet.builder().id(otherId).balance(BigDecimal.ZERO).currency("EUR").build();
        Transaction received = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(other).targetWallet(wallet)
            .amount(new BigDecimal("30.0000")).currency("EUR")
            .type(TransactionType.TRANSFER)
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByTargetWalletIdAndType(walletId, TransactionType.TRANSFER, pageable))
            .thenReturn(new PageImpl<>(List.of(received)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, "RECEIVED", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).type()).isEqualTo("RECEIVED");
    }

    @Test
    void getTransactions_withNullType_callsUnfilteredQuery() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable))
            .thenReturn(new PageImpl<>(List.of()));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, null, pageable);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void getTransactions_withInvalidType_throwsBusinessException() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() ->
            transactionService.getTransactions(walletId, OWNER_EMAIL, "INVALID", PageRequest.of(0, 20)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid transaction type filter: INVALID");
    }

    // ── helper ─────────────────────────────────────────────────────────────

    private Wallet walletWithEmail(UUID id, String email) {
        return Wallet.builder().id(id)
            .user(User.builder().id(UUID.randomUUID()).fullName("Owner").email(email)
                .documentNumber("12345678901").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .balance(BigDecimal.ZERO).currency("EUR").build();
    }
}
```

- [ ] **Step 2: Run tests — expect failures**

```bash
cd backend && ./mvnw test -Dtest=TransactionServiceTest -q 2>&1 | tail -20
```

Expected: compilation errors because `getTransactions` doesn't have the new signature yet.

- [ ] **Step 3: Implement filter logic in `TransactionService`**

Replace the full content of `TransactionService.java`:

```java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.exception.BusinessException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private static final Set<String> VALID_TYPES = Set.of("DEPOSIT", "WITHDRAWAL", "SENT", "RECEIVED");

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public Page<TransactionResponse> getTransactions(
            UUID walletId, String requestingEmail, String type, Pageable pageable) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getEmail().equals(requestingEmail)) {
            throw new ResourceNotFoundException("Wallet not found: " + walletId);
        }
        if (type != null && !VALID_TYPES.contains(type)) {
            throw new BusinessException(
                "Invalid transaction type filter: " + type +
                ". Valid values: DEPOSIT, WITHDRAWAL, SENT, RECEIVED");
        }
        Page<Transaction> page;
        if (type == null) {
            page = transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable);
        } else {
            page = switch (type) {
                case "DEPOSIT"    -> transactionRepository.findByTargetWalletIdAndType(walletId, TransactionType.DEPOSIT, pageable);
                case "WITHDRAWAL" -> transactionRepository.findBySourceWalletIdAndType(walletId, TransactionType.WITHDRAWAL, pageable);
                case "SENT"       -> transactionRepository.findBySourceWalletIdAndType(walletId, TransactionType.TRANSFER, pageable);
                case "RECEIVED"   -> transactionRepository.findByTargetWalletIdAndType(walletId, TransactionType.TRANSFER, pageable);
                default           -> throw new IllegalStateException("unreachable");
            };
        }
        return page.map(t -> toResponse(t, walletId));
    }

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
}
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
cd backend && ./mvnw test -Dtest=TransactionServiceTest -q 2>&1 | tail -5
```

Expected:
```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/liffeypay/liffeypay/service/TransactionService.java \
        backend/src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java
git commit -m "feat: add type filter to TransactionService with validation and dispatch"
```

---

## Task 3: Controller — add `type` query param (TDD)

**Files:**
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/controller/WalletControllerTest.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/controller/WalletController.java`

- [ ] **Step 1: Update `WalletControllerTest` — fix existing mocks, add invalidType test**

The two existing `getTransactions` mocks use 3-arg matchers; adding `type` makes it 4-arg. Also add one new test.

Find and replace the two existing `getTransactions` mock stubs and add the new test. The full updated sections are shown below.

Replace the `getTransactions_owner_returns200WithPage` test:

```java
@Test
void getTransactions_owner_returns200WithPage() throws Exception {
    TransactionResponse tx = new TransactionResponse(
        UUID.randomUUID(), "SENT", UUID.randomUUID(),
        new BigDecimal("50.0000"), "EUR", "COMPLETED", Instant.now()
    );
    Page<TransactionResponse> page = new PageImpl<>(List.of(tx));
    when(transactionService.getTransactions(eq(WALLET_ID), eq(OWNER_EMAIL), any(), any()))
        .thenReturn(page);

    mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID + "/transactions")
                    .with(jwt().jwt(j -> j.subject(OWNER_EMAIL))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].type").value("SENT"));
}
```

Replace the `getTransactions_nonOwner_returns404` test:

```java
@Test
void getTransactions_nonOwner_returns404() throws Exception {
    when(transactionService.getTransactions(eq(WALLET_ID), eq("hacker@evil.com"), any(), any()))
        .thenThrow(new ResourceNotFoundException("Wallet not found: " + WALLET_ID));

    mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID + "/transactions")
                    .with(jwt().jwt(j -> j.subject("hacker@evil.com"))))
            .andExpect(status().isNotFound());
}
```

Add the new test after `getTransactions_nonOwner_returns404`:

```java
@Test
void getTransactions_invalidType_returns400() throws Exception {
    when(transactionService.getTransactions(eq(WALLET_ID), eq(OWNER_EMAIL), eq("INVALID"), any()))
        .thenThrow(new BusinessException(
            "Invalid transaction type filter: INVALID. Valid values: DEPOSIT, WITHDRAWAL, SENT, RECEIVED"));

    mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID + "/transactions?type=INVALID")
                    .with(jwt().jwt(j -> j.subject(OWNER_EMAIL))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
}
```

Add the import for `BusinessException` at the top of `WalletControllerTest`:

```java
import com.liffeypay.liffeypay.exception.BusinessException;
```

- [ ] **Step 2: Run tests — expect failures**

```bash
cd backend && ./mvnw test -Dtest=WalletControllerTest -q 2>&1 | tail -20
```

Expected: compilation errors because `WalletController` still calls the old 3-arg `getTransactions`.

- [ ] **Step 3: Update `WalletController` — add `type` param**

Replace the `getTransactions` method in `WalletController.java`:

```java
@GetMapping("/{walletId}/transactions")
public ApiResponse<PageResponse<TransactionResponse>> getTransactions(
        @PathVariable UUID walletId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String type,
        @AuthenticationPrincipal Jwt jwt) {
    Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
    return ApiResponse.ok(PageResponse.from(
        transactionService.getTransactions(walletId, jwt.getSubject(), type, pageable)));
}
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
cd backend && ./mvnw test -Dtest=WalletControllerTest -q 2>&1 | tail -5
```

Expected:
```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 5: Run all unit tests to confirm no regressions**

```bash
cd backend && ./mvnw test -Dtest="TransactionServiceTest,WalletControllerTest,TransferControllerTest,DepositServiceTest,WithdrawalServiceTest" -q 2>&1 | tail -10
```

Expected: all pass, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/liffeypay/liffeypay/controller/WalletController.java \
        backend/src/test/java/com/liffeypay/liffeypay/controller/WalletControllerTest.java
git commit -m "feat: expose type filter query param on GET /wallets/{walletId}/transactions"
```

---

## Task 4: Integration tests

**Files:**
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/integration/TransactionHistoryIT.java`

- [ ] **Step 1: Add 5 integration tests to `TransactionHistoryIT`**

Add the following imports at the top of `TransactionHistoryIT.java` (alongside existing imports):

```java
import com.liffeypay.liffeypay.dto.DepositRequest;
import com.liffeypay.liffeypay.dto.WithdrawalRequest;
import org.springframework.http.HttpStatus;
```

Add the following 5 tests at the end of the class (before the closing `}`):

```java
@Test
@SuppressWarnings("unchecked")
void getHistory_filterByDeposit_returnsOnlyDeposits() {
    String aliceJwt = registerAndLogin("fd_alice@test.com", "password123");
    String bobJwt   = registerAndLogin("fd_bob@test.com", "password123");
    UUID aliceWallet = getWalletId(aliceJwt);
    UUID bobWallet   = getWalletId(bobJwt);

    // Creates a DEPOSIT transaction for Alice
    restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
        withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

    // Creates a SENT (TRANSFER) transaction for Alice
    wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
    TransferRequest req = new TransferRequest(aliceWallet, bobWallet, new BigDecimal("10.00"));
    restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, withAuth(req, aliceJwt), Map.class);
    wireMock.resetAll();

    ResponseEntity<Map> response = restTemplate.exchange(
        "/api/v1/wallets/" + aliceWallet + "/transactions?type=DEPOSIT",
        HttpMethod.GET, withAuth(aliceJwt), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> content = extractContent(response);
    assertThat(content).hasSize(1);
    assertThat(content.get(0).get("type")).isEqualTo("DEPOSIT");
}

@Test
@SuppressWarnings("unchecked")
void getHistory_filterBySent_returnsOnlySentTransfers() {
    String aliceJwt = registerAndLogin("fs_alice@test.com", "password123");
    String bobJwt   = registerAndLogin("fs_bob@test.com", "password123");
    UUID aliceWallet = getWalletId(aliceJwt);
    UUID bobWallet   = getWalletId(bobJwt);

    // Deposit so Alice has funds, then transfer (creates DEPOSIT + SENT)
    restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
        withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

    wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
    TransferRequest req = new TransferRequest(aliceWallet, bobWallet, new BigDecimal("10.00"));
    restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, withAuth(req, aliceJwt), Map.class);
    wireMock.resetAll();

    ResponseEntity<Map> response = restTemplate.exchange(
        "/api/v1/wallets/" + aliceWallet + "/transactions?type=SENT",
        HttpMethod.GET, withAuth(aliceJwt), Map.class);

    List<Map<String, Object>> content = extractContent(response);
    assertThat(content).hasSize(1);
    assertThat(content.get(0).get("type")).isEqualTo("SENT");
}

@Test
@SuppressWarnings("unchecked")
void getHistory_filterByReceived_returnsOnlyReceivedTransfers() {
    String aliceJwt = registerAndLogin("fr_alice@test.com", "password123");
    String bobJwt   = registerAndLogin("fr_bob@test.com", "password123");
    UUID aliceWallet = getWalletId(aliceJwt);
    UUID bobWallet   = getWalletId(bobJwt);

    // Alice deposits and sends to Bob; query Bob's wallet for RECEIVED
    restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
        withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

    wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
    TransferRequest req = new TransferRequest(aliceWallet, bobWallet, new BigDecimal("10.00"));
    restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, withAuth(req, aliceJwt), Map.class);
    wireMock.resetAll();

    ResponseEntity<Map> response = restTemplate.exchange(
        "/api/v1/wallets/" + bobWallet + "/transactions?type=RECEIVED",
        HttpMethod.GET, withAuth(bobJwt), Map.class);

    List<Map<String, Object>> content = extractContent(response);
    assertThat(content).hasSize(1);
    assertThat(content.get(0).get("type")).isEqualTo("RECEIVED");
}

@Test
@SuppressWarnings("unchecked")
void getHistory_filterByWithdrawal_returnsOnlyWithdrawals() {
    String aliceJwt = registerAndLogin("fw_alice@test.com", "password123");
    UUID aliceWallet = getWalletId(aliceJwt);

    // Deposit then withdraw (creates DEPOSIT + WITHDRAWAL)
    restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
        withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

    wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
    restTemplate.exchange("/api/v1/wallets/me/withdraw", HttpMethod.POST,
        withAuth(new WithdrawalRequest(new BigDecimal("30.00")), aliceJwt), Map.class);
    wireMock.resetAll();

    ResponseEntity<Map> response = restTemplate.exchange(
        "/api/v1/wallets/" + aliceWallet + "/transactions?type=WITHDRAWAL",
        HttpMethod.GET, withAuth(aliceJwt), Map.class);

    List<Map<String, Object>> content = extractContent(response);
    assertThat(content).hasSize(1);
    assertThat(content.get(0).get("type")).isEqualTo("WITHDRAWAL");
}

@Test
@SuppressWarnings("unchecked")
void getHistory_invalidType_returns400() {
    String jwt = registerAndLogin("iv_user@test.com", "password123");
    UUID walletId = getWalletId(jwt);

    ResponseEntity<Map> response = restTemplate.exchange(
        "/api/v1/wallets/" + walletId + "/transactions?type=INVALID",
        HttpMethod.GET, withAuth(jwt), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((Map<String, Object>) response.getBody()).get("success")).isEqualTo(false);
}

@SuppressWarnings("unchecked")
private List<Map<String, Object>> extractContent(ResponseEntity<Map> response) {
    Map<String, Object> data = (Map<String, Object>)
        ((Map<String, Object>) response.getBody()).get("data");
    return (List<Map<String, Object>>) data.get("content");
}
```

- [ ] **Step 2: Run integration tests**

```bash
cd backend && ./mvnw test -Dtest=TransactionHistoryIT 2>&1 | tail -15
```

Expected:
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: Run full test suite to confirm no regressions**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```

Expected: all tests pass, `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/liffeypay/liffeypay/integration/TransactionHistoryIT.java
git commit -m "test: add integration tests for transaction history type filter"
```

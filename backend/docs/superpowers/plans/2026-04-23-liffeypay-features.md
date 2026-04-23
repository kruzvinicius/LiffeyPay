# LiffeyPay — 4 Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MERCHANT transfer restriction, transaction history endpoint, external authorization service, and async post-transfer notifications to LiffeyPay.

**Architecture:** Each feature extends the existing layered structure (controller → service → repository). Features 3 and 4 both inject into `TransferService` at different points: authorization runs before acquiring pessimistic locks; notification is published after the transaction commits. All HTTP integrations are behind interfaces for testability.

**Tech Stack:** Spring Boot 3.4.3, Java 21, Mockito + MockMvc (tests), `RestClient` (Spring 6 HTTP client), Spring Application Events + `@Async` (notifications).

---

## File Map

**Feature 1 — MERCHANT Restriction**
- Create: `src/main/java/com/liffeypay/liffeypay/exception/MerchantTransferNotAllowedException.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`

**Feature 2 — Transaction History**
- Create: `src/main/java/com/liffeypay/liffeypay/dto/TransactionResponse.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/domain/repository/TransactionRepository.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/TransactionService.java`
- Create: `src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/controller/WalletController.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/controller/WalletControllerTest.java`

**Feature 3 — External Authorization**
- Create: `src/main/java/com/liffeypay/liffeypay/service/AuthorizationService.java`
- Create: `src/main/java/com/liffeypay/liffeypay/exception/TransferNotAuthorizedException.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/HttpAuthorizationService.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`

**Feature 4 — Async Notifications**
- Create: `src/main/java/com/liffeypay/liffeypay/service/TransferCompletedEvent.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/NotificationService.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/HttpNotificationService.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/NotificationListener.java`
- Create: `src/test/java/com/liffeypay/liffeypay/service/NotificationListenerTest.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/LiffeyPayApplication.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`

---

## Task 1 — Feature 1: MERCHANT Transfer Restriction

**Files:**
- Create: `src/main/java/com/liffeypay/liffeypay/exception/MerchantTransferNotAllowedException.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`

- [ ] **Step 1: Update setUp() in TransferServiceTest to add INDIVIDUAL user to wallets**

The existing tests do not set `user` on the wallet. The MERCHANT check calls `source.getUser().getUserType()`, which will NPE if user is null. Update `@BeforeEach` and the lock order test:

```java
// Replace the @BeforeEach and the lock-order test body in TransferServiceTest

@BeforeEach
void setUp() {
    source = Wallet.builder()
        .id(sourceId)
        .balance(new BigDecimal("100.0000"))
        .currency("EUR")
        .user(User.builder()
            .id(UUID.randomUUID()).fullName("Sender").email("sender@test.com")
            .documentNumber("12345678901").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build())
        .build();
    target = Wallet.builder()
        .id(targetId)
        .balance(new BigDecimal("50.0000"))
        .currency("EUR")
        .user(User.builder()
            .id(UUID.randomUUID()).fullName("Receiver").email("receiver@test.com")
            .documentNumber("98765432100").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build())
        .build();
}
```

Also update the lock-order test to add users to `bigSourceWallet` and `smallTargetWallet`:

```java
Wallet bigSourceWallet = Wallet.builder()
    .id(bigSource).balance(new BigDecimal("100.0000")).currency("EUR")
    .user(User.builder().id(UUID.randomUUID()).fullName("Big").email("big@test.com")
        .documentNumber("11111111111").passwordHash("hash").userType(UserType.INDIVIDUAL).build())
    .build();
Wallet smallTargetWallet = Wallet.builder()
    .id(smallTarget).balance(BigDecimal.ZERO).currency("EUR")
    .user(User.builder().id(UUID.randomUUID()).fullName("Small").email("small@test.com")
        .documentNumber("22222222222").passwordHash("hash").userType(UserType.INDIVIDUAL).build())
    .build();
```

- [ ] **Step 2: Write the failing test for MERCHANT check**

Add this test to `TransferServiceTest`:

```java
@Test
void transfer_merchantSource_throwsMerchantTransferNotAllowedException() {
    Wallet merchantWallet = Wallet.builder()
        .id(sourceId).balance(new BigDecimal("100.0000")).currency("EUR")
        .user(User.builder()
            .id(UUID.randomUUID()).fullName("Shop").email("shop@test.com")
            .documentNumber("12345678000100").passwordHash("hash")
            .userType(UserType.MERCHANT).build())
        .build();

    when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(merchantWallet));
    when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> transferService.transfer(
        new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
        .isInstanceOf(MerchantTransferNotAllowedException.class);

    verify(transactionRepository, never()).save(any());
}
```

- [ ] **Step 3: Run test — expect compilation error**

```bash
./mvnw test -Dtest=TransferServiceTest#transfer_merchantSource_throwsMerchantTransferNotAllowedException
```

Expected: BUILD FAILURE — `MerchantTransferNotAllowedException` does not exist yet.

- [ ] **Step 4: Create MerchantTransferNotAllowedException**

```java
// src/main/java/com/liffeypay/liffeypay/exception/MerchantTransferNotAllowedException.java
package com.liffeypay.liffeypay.exception;

import java.util.UUID;

public class MerchantTransferNotAllowedException extends BusinessException {
    public MerchantTransferNotAllowedException(UUID walletId) {
        super("Merchant wallet cannot initiate transfers: " + walletId);
    }
}
```

- [ ] **Step 5: Run test again — expect test failure (logic not yet in service)**

```bash
./mvnw test -Dtest=TransferServiceTest#transfer_merchantSource_throwsMerchantTransferNotAllowedException
```

Expected: FAIL — no exception thrown.

- [ ] **Step 6: Add MERCHANT check to TransferService.executeTransfer()**

In `executeTransfer()`, after resolving `source` and `target` from `first`/`second`, add:

```java
// In TransferService.executeTransfer(), after: Wallet target = sourceFirst ? second : first;
if (source.getUser().getUserType() == UserType.MERCHANT) {
    throw new MerchantTransferNotAllowedException(source.getId());
}
```

Add the import: `import com.liffeypay.liffeypay.domain.model.UserType;`

- [ ] **Step 7: Add handler to GlobalExceptionHandler**

Add this method before the generic `handleBusiness` method:

```java
@ExceptionHandler(MerchantTransferNotAllowedException.class)
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public ApiResponse<Void> handleMerchantTransfer(MerchantTransferNotAllowedException ex) {
    log.warn("Transfer rejected - merchant source: {}", ex.getMessage());
    return ApiResponse.error(ex.getMessage());
}
```

Add the import: `import com.liffeypay.liffeypay.exception.MerchantTransferNotAllowedException;`

- [ ] **Step 8: Run all tests**

```bash
./mvnw test
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/exception/MerchantTransferNotAllowedException.java \
        src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java \
        src/main/java/com/liffeypay/liffeypay/service/TransferService.java \
        src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java
git commit -m "feat: reject transfers initiated by MERCHANT wallets"
```

---

## Task 2 — Feature 2: TransactionService + Repository Method

**Files:**
- Create: `src/main/java/com/liffeypay/liffeypay/dto/TransactionResponse.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/domain/repository/TransactionRepository.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/TransactionService.java`
- Create: `src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java`

- [ ] **Step 1: Write failing tests for TransactionService**

Create `src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java`:

```java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransactionResponse;
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

    private final UUID walletId    = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
    private final UUID otherId     = UUID.fromString("bbbb0000-0000-0000-0000-000000000002");
    private static final String OWNER_EMAIL = "owner@test.com";

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
            transactionService.getTransactions(walletId, OWNER_EMAIL, pageable);

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
            transactionService.getTransactions(walletId, "hacker@evil.com", PageRequest.of(0, 20)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getTransactions_walletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findById(walletId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            transactionService.getTransactions(walletId, OWNER_EMAIL, PageRequest.of(0, 20)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private Wallet walletWithEmail(UUID id, String email) {
        return Wallet.builder().id(id)
            .user(User.builder().id(UUID.randomUUID()).fullName("Owner").email(email)
                .documentNumber("12345678901").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .balance(BigDecimal.ZERO).currency("EUR").build();
    }
}
```

- [ ] **Step 2: Run test — expect compilation error**

```bash
./mvnw test -Dtest=TransactionServiceTest
```

Expected: BUILD FAILURE — `TransactionService` does not exist.

- [ ] **Step 3: Create TransactionResponse DTO**

```java
// src/main/java/com/liffeypay/liffeypay/dto/TransactionResponse.java
package com.liffeypay.liffeypay.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
    UUID id,
    String type,
    UUID counterpartWalletId,
    BigDecimal amount,
    String currency,
    String status,
    Instant createdAt
) {}
```

- [ ] **Step 4: Add pagination query to TransactionRepository**

Add this method to `TransactionRepository`:

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

Page<Transaction> findAllBySourceWalletIdOrTargetWalletId(
    UUID sourceWalletId, UUID targetWalletId, Pageable pageable);
```

- [ ] **Step 5: Create TransactionService**

```java
// src/main/java/com/liffeypay/liffeypay/service/TransactionService.java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public Page<TransactionResponse> getTransactions(
            UUID walletId, String requestingEmail, Pageable pageable) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getEmail().equals(requestingEmail)) {
            throw new ResourceNotFoundException("Wallet not found: " + walletId);
        }
        return transactionRepository
            .findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable)
            .map(t -> toResponse(t, walletId));
    }

    private TransactionResponse toResponse(Transaction t, UUID walletId) {
        boolean sent = t.getSourceWallet().getId().equals(walletId);
        UUID counterpart = sent ? t.getTargetWallet().getId() : t.getSourceWallet().getId();
        return new TransactionResponse(
            t.getId(), sent ? "SENT" : "RECEIVED", counterpart,
            t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt()
        );
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=TransactionServiceTest
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/dto/TransactionResponse.java \
        src/main/java/com/liffeypay/liffeypay/domain/repository/TransactionRepository.java \
        src/main/java/com/liffeypay/liffeypay/service/TransactionService.java \
        src/test/java/com/liffeypay/liffeypay/service/TransactionServiceTest.java
git commit -m "feat: add TransactionService and paginated history query"
```

---

## Task 3 — Feature 2: Transaction History Endpoint

**Files:**
- Modify: `src/main/java/com/liffeypay/liffeypay/controller/WalletController.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/controller/WalletControllerTest.java`

- [ ] **Step 1: Add @MockBean TransactionService and write failing tests in WalletControllerTest**

Add to `WalletControllerTest` (alongside existing `@MockBean WalletService`):

```java
@MockBean TransactionService transactionService;
```

Add these two tests:

```java
@Test
void getTransactions_owner_returns200WithPage() throws Exception {
    TransactionResponse tx = new TransactionResponse(
        UUID.randomUUID(), "SENT", UUID.randomUUID(),
        new BigDecimal("50.0000"), "EUR", "COMPLETED", Instant.now()
    );
    Page<TransactionResponse> page = new PageImpl<>(List.of(tx));
    when(transactionService.getTransactions(eq(WALLET_ID), eq(OWNER_EMAIL), any()))
        .thenReturn(page);

    mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID + "/transactions")
            .with(jwt().jwt(j -> j.subject(OWNER_EMAIL))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content[0].type").value("SENT"));
}

@Test
void getTransactions_nonOwner_returns404() throws Exception {
    when(transactionService.getTransactions(eq(WALLET_ID), eq("hacker@evil.com"), any()))
        .thenThrow(new ResourceNotFoundException("Wallet not found: " + WALLET_ID));

    mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID + "/transactions")
            .with(jwt().jwt(j -> j.subject("hacker@evil.com"))))
        .andExpect(status().isNotFound());
}
```

Add these imports to `WalletControllerTest`:

```java
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.service.TransactionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
```

- [ ] **Step 2: Run tests — expect failure**

```bash
./mvnw test -Dtest=WalletControllerTest
```

Expected: FAIL — endpoint not found (404 for the new test) and context error if `TransactionService` is not yet in `WalletController`.

- [ ] **Step 3: Add endpoint to WalletController**

Replace the full `WalletController` content:

```java
package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.dto.WalletResponse;
import com.liffeypay.liffeypay.service.TransactionService;
import com.liffeypay.liffeypay.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TransactionService transactionService;

    @GetMapping("/me")
    public ApiResponse<WalletResponse> getMyWallet(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(walletService.getByOwnerEmail(jwt.getSubject()));
    }

    @GetMapping("/{walletId}")
    public ApiResponse<WalletResponse> getById(
            @PathVariable UUID walletId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(walletService.getById(walletId, jwt.getSubject()));
    }

    @GetMapping("/{walletId}/transactions")
    public ApiResponse<Page<TransactionResponse>> getTransactions(
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(transactionService.getTransactions(walletId, jwt.getSubject(), pageable));
    }
}
```

- [ ] **Step 4: Run all tests**

```bash
./mvnw test
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/controller/WalletController.java \
        src/test/java/com/liffeypay/liffeypay/controller/WalletControllerTest.java
git commit -m "feat: add GET /wallets/{id}/transactions endpoint"
```

---

## Task 4 — Feature 3: Authorization Interface + Exception + Handler

**Files:**
- Create: `src/main/java/com/liffeypay/liffeypay/service/AuthorizationService.java`
- Create: `src/main/java/com/liffeypay/liffeypay/exception/TransferNotAuthorizedException.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create AuthorizationService interface**

```java
// src/main/java/com/liffeypay/liffeypay/service/AuthorizationService.java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.dto.TransferRequest;

public interface AuthorizationService {
    void authorize(TransferRequest request);
}
```

- [ ] **Step 2: Create TransferNotAuthorizedException**

```java
// src/main/java/com/liffeypay/liffeypay/exception/TransferNotAuthorizedException.java
package com.liffeypay.liffeypay.exception;

public class TransferNotAuthorizedException extends BusinessException {
    public TransferNotAuthorizedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: Add handler to GlobalExceptionHandler**

Add this method before the generic `handleBusiness` method:

```java
@ExceptionHandler(TransferNotAuthorizedException.class)
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public ApiResponse<Void> handleTransferNotAuthorized(TransferNotAuthorizedException ex) {
    log.warn("Transfer not authorized: {}", ex.getMessage());
    return ApiResponse.error(ex.getMessage());
}
```

Add import: `import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;`

- [ ] **Step 4: Run all tests**

```bash
./mvnw test
```

Expected: BUILD SUCCESS, all tests pass (no behavior changed yet).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/service/AuthorizationService.java \
        src/main/java/com/liffeypay/liffeypay/exception/TransferNotAuthorizedException.java \
        src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java
git commit -m "feat: add AuthorizationService interface and TransferNotAuthorizedException"
```

---

## Task 5 — Feature 3: HttpAuthorizationService + Wire into TransferService

**Files:**
- Create: `src/main/java/com/liffeypay/liffeypay/service/HttpAuthorizationService.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`

- [ ] **Step 1: Add @Mock AuthorizationService to TransferServiceTest and write failing tests**

Add this field to `TransferServiceTest` (before `@InjectMocks`):

```java
@Mock AuthorizationService authorizationService;
```

Add import: `import com.liffeypay.liffeypay.service.AuthorizationService;`

Add these two tests:

```java
@Test
void transfer_deniedByAuthorizer_throwsTransferNotAuthorizedException() {
    doThrow(new TransferNotAuthorizedException("Transfer denied"))
        .when(authorizationService).authorize(any());

    assertThatThrownBy(() -> transferService.transfer(
        new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
        .isInstanceOf(TransferNotAuthorizedException.class);

    verify(walletRepository, never()).findByIdWithLock(any());
    verify(transactionRepository, never()).save(any());
}

@Test
void transfer_authorizerUnavailable_throwsTransferNotAuthorizedException() {
    doThrow(new TransferNotAuthorizedException("Authorization service unavailable"))
        .when(authorizationService).authorize(any());

    assertThatThrownBy(() -> transferService.transfer(
        new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
        .isInstanceOf(TransferNotAuthorizedException.class);
}
```

Add imports:

```java
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import static org.mockito.Mockito.doThrow;
```

- [ ] **Step 2: Run tests — expect test failure**

```bash
./mvnw test -Dtest=TransferServiceTest
```

Expected: FAIL — `assertThatThrownBy` fails because `authorize()` is not yet called in `TransferService`, so no exception is thrown.

- [ ] **Step 3: Create HttpAuthorizationService**

```java
// src/main/java/com/liffeypay/liffeypay/service/HttpAuthorizationService.java
package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Service
@Slf4j
public class HttpAuthorizationService implements AuthorizationService {

    private final RestClient restClient;

    public HttpAuthorizationService(
        @Value("${app.authorization.url}") String url,
        @Value("${app.authorization.timeout-ms:3000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public void authorize(TransferRequest request) {
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

    private record AuthorizationResponse(String status) {}
}
```

- [ ] **Step 4: Add config to application.properties**

```properties
# External authorization
app.authorization.url=${AUTHORIZATION_URL:https://run.mocky.io/v3/5794d450-d2e2-4412-8131-73d0293ac1cc}
app.authorization.timeout-ms=${AUTHORIZATION_TIMEOUT_MS:3000}
```

- [ ] **Step 5: Wire AuthorizationService into TransferService**

Add field (Lombok `@RequiredArgsConstructor` will inject it):

```java
private final AuthorizationService authorizationService;
```

Add call at the top of `executeTransfer()`, before the lock acquisition block:

```java
authorizationService.authorize(request);
```

- [ ] **Step 6: Run all tests**

```bash
./mvnw test
```

Expected: BUILD SUCCESS. Existing tests still pass because Mockito's default for void methods is do-nothing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/service/HttpAuthorizationService.java \
        src/main/java/com/liffeypay/liffeypay/service/TransferService.java \
        src/main/resources/application.properties \
        src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java
git commit -m "feat: add external authorization check before transfer execution"
```

---

## Task 6 — Feature 4: Notification Scaffolding + Listener

**Files:**
- Create: `src/main/java/com/liffeypay/liffeypay/service/TransferCompletedEvent.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/NotificationService.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/HttpNotificationService.java`
- Create: `src/main/java/com/liffeypay/liffeypay/service/NotificationListener.java`
- Create: `src/test/java/com/liffeypay/liffeypay/service/NotificationListenerTest.java`
- Modify: `src/main/java/com/liffeypay/liffeypay/LiffeyPayApplication.java`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Write failing test for NotificationListener**

Create `src/test/java/com/liffeypay/liffeypay/service/NotificationListenerTest.java`:

```java
package com.liffeypay.liffeypay.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationListenerTest {

    @Mock NotificationService notificationService;
    @InjectMocks NotificationListener notificationListener;

    @Test
    void onTransferCompleted_callsNotifyWithEvent() {
        TransferCompletedEvent event = new TransferCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("100.0000"), "EUR"
        );

        notificationListener.onTransferCompleted(event);

        verify(notificationService).notify(event);
    }
}
```

- [ ] **Step 2: Run test — expect compilation error**

```bash
./mvnw test -Dtest=NotificationListenerTest
```

Expected: BUILD FAILURE — `NotificationListener`, `NotificationService`, `TransferCompletedEvent` do not exist yet.

- [ ] **Step 3: Create TransferCompletedEvent**

```java
// src/main/java/com/liffeypay/liffeypay/service/TransferCompletedEvent.java
package com.liffeypay.liffeypay.service;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferCompletedEvent(
    UUID transactionId,
    UUID sourceWalletId,
    UUID targetWalletId,
    BigDecimal amount,
    String currency
) {}
```

- [ ] **Step 4: Create NotificationService interface**

```java
// src/main/java/com/liffeypay/liffeypay/service/NotificationService.java
package com.liffeypay.liffeypay.service;

public interface NotificationService {
    void notify(TransferCompletedEvent event);
}
```

- [ ] **Step 5: Create HttpNotificationService**

```java
// src/main/java/com/liffeypay/liffeypay/service/HttpNotificationService.java
package com.liffeypay.liffeypay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class HttpNotificationService implements NotificationService {

    private final RestClient restClient;

    public HttpNotificationService(@Value("${app.notification.url}") String url) {
        this.restClient = RestClient.builder().baseUrl(url).build();
    }

    @Override
    public void notify(TransferCompletedEvent event) {
        try {
            restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to notify for transaction {}: {}", event.transactionId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Create NotificationListener**

```java
// src/main/java/com/liffeypay/liffeypay/service/NotificationListener.java
package com.liffeypay.liffeypay.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCompleted(TransferCompletedEvent event) {
        notificationService.notify(event);
    }
}
```

- [ ] **Step 7: Add @EnableAsync to LiffeyPayApplication**

```java
package com.liffeypay.liffeypay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class LiffeyPayApplication {
    public static void main(String[] args) {
        SpringApplication.run(LiffeyPayApplication.class, args);
    }
}
```

- [ ] **Step 8: Add config to application.properties**

```properties
# Notification service
app.notification.url=${NOTIFICATION_URL:http://localhost:8081/notify}
```

- [ ] **Step 9: Run tests**

```bash
./mvnw test -Dtest=NotificationListenerTest
```

Expected: BUILD SUCCESS, 1 test passes.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/service/TransferCompletedEvent.java \
        src/main/java/com/liffeypay/liffeypay/service/NotificationService.java \
        src/main/java/com/liffeypay/liffeypay/service/HttpNotificationService.java \
        src/main/java/com/liffeypay/liffeypay/service/NotificationListener.java \
        src/main/java/com/liffeypay/liffeypay/LiffeyPayApplication.java \
        src/main/resources/application.properties \
        src/test/java/com/liffeypay/liffeypay/service/NotificationListenerTest.java
git commit -m "feat: add async notification service triggered after transfer commit"
```

---

## Task 7 — Feature 4: Wire Event Publisher into TransferService

**Files:**
- Modify: `src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`

- [ ] **Step 1: Add @Mock ApplicationEventPublisher to TransferServiceTest and write failing test**

Add field to `TransferServiceTest`:

```java
@Mock ApplicationEventPublisher eventPublisher;
```

Add import: `import org.springframework.context.ApplicationEventPublisher;`

Add this test:

```java
@Test
void transfer_success_publishesTransferCompletedEvent() {
    when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
    when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));
    when(transactionRepository.save(any()))
        .thenReturn(savedTx(sourceId, targetId, new BigDecimal("10.0000")));

    transferService.transfer(
        new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null);

    verify(eventPublisher).publishEvent(any(TransferCompletedEvent.class));
}
```

Add import: `import com.liffeypay.liffeypay.service.TransferCompletedEvent;`

- [ ] **Step 2: Run test — expect failure**

```bash
./mvnw test -Dtest=TransferServiceTest#transfer_success_publishesTransferCompletedEvent
```

Expected: FAIL — `eventPublisher.publishEvent()` never called (field not wired yet).

- [ ] **Step 3: Wire ApplicationEventPublisher into TransferService**

Add field (Lombok `@RequiredArgsConstructor` handles injection):

```java
private final ApplicationEventPublisher eventPublisher;
```

Add import: `import org.springframework.context.ApplicationEventPublisher;`

In `executeTransfer()`, after `transactionRepository.save(transaction)` and the log statement, add:

```java
eventPublisher.publishEvent(new TransferCompletedEvent(
    saved.getId(),
    source.getId(),
    target.getId(),
    saved.getAmount(),
    saved.getCurrency()
));
```

Add import: `import com.liffeypay.liffeypay.service.TransferCompletedEvent;`

- [ ] **Step 4: Run all tests**

```bash
./mvnw test
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/liffeypay/liffeypay/service/TransferService.java \
        src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java
git commit -m "feat: publish TransferCompletedEvent after successful transfer"
```

---

## Final Verification

- [ ] Run the full test suite one last time:

```bash
./mvnw test
```

Expected: BUILD SUCCESS, zero failures.

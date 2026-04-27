# Transfer by Email Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /api/v1/transfers/email` so authenticated users can transfer funds to a recipient identified by email instead of wallet UUID.

**Architecture:** Source wallet is derived from the JWT subject — the caller never sends their own wallet ID. Target wallet is resolved from `recipientEmail` in the request body using the existing `findByUserEmail` query. After resolving both wallet IDs, the method delegates to the existing private `executeTransfer()`, which preserves the pessimistic-lock ordering, MERCHANT check, balance check, authorization service call, and async notification.

**Tech Stack:** Spring Boot 3.4.3, Java 21, JUnit 5, Mockito, MockMvc (`@WebMvcTest`), Testcontainers (via `IntegrationTestBase`), AssertJ.

---

## File Map

- Create: `backend/src/main/java/com/liffeypay/liffeypay/exception/SelfTransferException.java`
- Create: `backend/src/main/java/com/liffeypay/liffeypay/dto/TransferByEmailRequest.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/service/TransferService.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/controller/TransferController.java`
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/controller/TransferControllerTest.java`
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/integration/TransferIT.java`

---

## Task 1 — SelfTransferException + GlobalExceptionHandler

**Files:**
- Create: `backend/src/main/java/com/liffeypay/liffeypay/exception/SelfTransferException.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create SelfTransferException**

```java
// backend/src/main/java/com/liffeypay/liffeypay/exception/SelfTransferException.java
package com.liffeypay.liffeypay.exception;

public class SelfTransferException extends BusinessException {
    public SelfTransferException() {
        super("Self-transfer is not allowed");
    }
}
```

- [ ] **Step 2: Add handler to GlobalExceptionHandler**

Add this method to `GlobalExceptionHandler.java` before the generic `handleBusiness` method (around line 55, after `handleMerchantTransfer`):

```java
@ExceptionHandler(SelfTransferException.class)
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public ApiResponse<Void> handleSelfTransfer(SelfTransferException ex) {
    return ApiResponse.error(ex.getMessage());
}
```

Also add the import at the top of the class:
```java
import com.liffeypay.liffeypay.exception.SelfTransferException;
```

- [ ] **Step 3: Verify compilation**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/liffeypay/liffeypay/exception/SelfTransferException.java \
        backend/src/main/java/com/liffeypay/liffeypay/exception/GlobalExceptionHandler.java
git commit -m "feat: add SelfTransferException mapped to HTTP 422"
```

---

## Task 2 — TransferByEmailRequest DTO

**Files:**
- Create: `backend/src/main/java/com/liffeypay/liffeypay/dto/TransferByEmailRequest.java`

- [ ] **Step 1: Create TransferByEmailRequest**

```java
// backend/src/main/java/com/liffeypay/liffeypay/dto/TransferByEmailRequest.java
package com.liffeypay.liffeypay.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransferByEmailRequest(

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Invalid email format")
    String recipientEmail,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "Invalid monetary amount format")
    BigDecimal amount
) {}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/liffeypay/liffeypay/dto/TransferByEmailRequest.java
git commit -m "feat: add TransferByEmailRequest DTO"
```

---

## Task 3 — TransferService.transferByEmail() (TDD)

**Files:**
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/service/TransferService.java`

- [ ] **Step 1: Add 4 failing tests to TransferServiceTest**

Add these imports to `TransferServiceTest.java` (after the existing imports):

```java
import com.liffeypay.liffeypay.exception.SelfTransferException;
import java.math.BigDecimal;
```

Add these four test methods at the end of `TransferServiceTest`, before the closing `}`:

```java
@Test
void transferByEmail_happyPath_debitSourceCreditTarget() {
    when(walletRepository.findByUserEmail("sender@test.com")).thenReturn(Optional.of(source));
    when(walletRepository.findByUserEmail("receiver@test.com")).thenReturn(Optional.of(target));
    when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
    when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));
    when(transactionRepository.save(any())).thenReturn(savedTx(sourceId, targetId, new BigDecimal("40.0000")));

    TransferResponse response = transferService.transferByEmail(
        "sender@test.com", "receiver@test.com", new BigDecimal("40.00"), null);

    assertThat(source.getBalance()).isEqualByComparingTo("60.0000");
    assertThat(target.getBalance()).isEqualByComparingTo("90.0000");
    assertThat(response.sourceWalletId()).isEqualTo(sourceId);
    assertThat(response.targetWalletId()).isEqualTo(targetId);
}

@Test
void transferByEmail_selfTransfer_throwsSelfTransferException() {
    when(walletRepository.findByUserEmail("sender@test.com")).thenReturn(Optional.of(source));

    assertThatThrownBy(() -> transferService.transferByEmail(
        "sender@test.com", "sender@test.com", new BigDecimal("10.00"), null))
        .isInstanceOf(SelfTransferException.class);

    verify(transactionRepository, never()).save(any());
}

@Test
void transferByEmail_recipientNotFound_throwsResourceNotFoundException() {
    when(walletRepository.findByUserEmail("sender@test.com")).thenReturn(Optional.of(source));
    when(walletRepository.findByUserEmail("ghost@test.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> transferService.transferByEmail(
        "sender@test.com", "ghost@test.com", new BigDecimal("10.00"), null))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(transactionRepository, never()).save(any());
}

@Test
void transferByEmail_senderNotFound_throwsResourceNotFoundException() {
    when(walletRepository.findByUserEmail("nobody@test.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> transferService.transferByEmail(
        "nobody@test.com", "receiver@test.com", new BigDecimal("10.00"), null))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(transactionRepository, never()).save(any());
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
cd backend && ./mvnw test-compile -q 2>&1 | tail -5
```

Expected: BUILD FAILURE — `transferByEmail` does not exist in `TransferService`.

- [ ] **Step 3: Add transferByEmail to TransferService**

Add the following two methods to `TransferService.java`. Insert them after the existing `transfer()` method and before the private `executeTransfer()` method:

```java
@Transactional
public TransferResponse transferByEmail(
        String senderEmail, String recipientEmail, BigDecimal amount, String idempotencyKey) {
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
            .map(this::toResponse)
            .orElseGet(() -> executeTransferByEmail(senderEmail, recipientEmail, amount, idempotencyKey));
    }
    return executeTransferByEmail(senderEmail, recipientEmail, amount, null);
}

private TransferResponse executeTransferByEmail(
        String senderEmail, String recipientEmail, BigDecimal amount, String idempotencyKey) {
    Wallet source = walletRepository.findByUserEmail(senderEmail)
        .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for: " + senderEmail));
    Wallet target = walletRepository.findByUserEmail(recipientEmail)
        .orElseThrow(() -> new ResourceNotFoundException("No account found for email: " + recipientEmail));

    if (source.getId().equals(target.getId())) {
        throw new SelfTransferException();
    }

    return executeTransfer(new TransferRequest(source.getId(), target.getId(), amount), idempotencyKey);
}
```

Also add this import at the top of `TransferService.java`:

```java
import com.liffeypay.liffeypay.exception.SelfTransferException;
import java.math.BigDecimal;
```

- [ ] **Step 4: Run TransferServiceTest**

```bash
cd backend && ./mvnw test -Dtest=TransferServiceTest -q
```

Expected: BUILD SUCCESS, all 16 tests pass (12 existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/liffeypay/liffeypay/service/TransferService.java \
        backend/src/test/java/com/liffeypay/liffeypay/service/TransferServiceTest.java
git commit -m "feat: add TransferService.transferByEmail() with self-transfer guard"
```

---

## Task 4 — TransferController new endpoint (TDD)

**Files:**
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/controller/TransferControllerTest.java`
- Modify: `backend/src/main/java/com/liffeypay/liffeypay/controller/TransferController.java`

- [ ] **Step 1: Add 2 failing tests to TransferControllerTest**

Add these imports to `TransferControllerTest.java`:

```java
import com.liffeypay.liffeypay.dto.TransferByEmailRequest;
import com.liffeypay.liffeypay.exception.SelfTransferException;
```

Add these two test methods at the end of `TransferControllerTest`, before the closing `}`:

```java
@Test
void transferByEmail_authenticated_returns201() throws Exception {
    when(transferService.transferByEmail(
            eq("sender@test.com"), eq("bob@example.com"), any(), isNull()))
        .thenReturn(mockResponse());

    mockMvc.perform(post("/api/v1/transfers/email")
            .with(jwt().jwt(j -> j.subject("sender@test.com")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new TransferByEmailRequest("bob@example.com", new BigDecimal("30.00")))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.transactionId").value(TX_ID.toString()));
}

@Test
void transferByEmail_unauthenticated_returns401() throws Exception {
    mockMvc.perform(post("/api/v1/transfers/email")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new TransferByEmailRequest("bob@example.com", new BigDecimal("30.00")))))
        .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: Run TransferControllerTest — expect failure**

```bash
cd backend && ./mvnw test -Dtest=TransferControllerTest -q
```

Expected: the 2 new tests fail — `transferByEmail_authenticated_returns201` gets 404 (endpoint missing), `transferByEmail_unauthenticated_returns401` may pass or fail. Existing 9 tests should still pass.

- [ ] **Step 3: Add endpoint to TransferController**

Replace the full content of `TransferController.java`:

```java
// backend/src/main/java/com/liffeypay/liffeypay/controller/TransferController.java
package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.dto.TransferByEmailRequest;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.dto.TransferResponse;
import com.liffeypay.liffeypay.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransferService transferService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferResponse> transfer(
            @RequestBody @Valid TransferRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return ApiResponse.ok(transferService.transfer(request, idempotencyKey), "Transfer completed successfully");
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferResponse> transferByEmail(
            @RequestBody @Valid TransferByEmailRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(
            transferService.transferByEmail(jwt.getSubject(), request.recipientEmail(), request.amount(), idempotencyKey),
            "Transfer completed successfully");
    }
}
```

- [ ] **Step 4: Run TransferControllerTest**

```bash
cd backend && ./mvnw test -Dtest=TransferControllerTest -q
```

Expected: BUILD SUCCESS, all 11 tests pass (9 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/liffeypay/liffeypay/controller/TransferController.java \
        backend/src/test/java/com/liffeypay/liffeypay/controller/TransferControllerTest.java
git commit -m "feat: add POST /api/v1/transfers/email endpoint"
```

---

## Task 5 — Integration tests

**Files:**
- Modify: `backend/src/test/java/com/liffeypay/liffeypay/integration/TransferIT.java`

- [ ] **Step 1: Add 2 integration tests to TransferIT**

Add this import to `TransferIT.java`:

```java
import com.liffeypay.liffeypay.dto.TransferByEmailRequest;
```

Add these two test methods at the end of `TransferIT`, before the closing `}`:

```java
@Test
@SuppressWarnings("unchecked")
void transferByEmail_happyPath_balancesUpdatedInDb() {
    wireMock.stubFor(get(urlEqualTo("/authorize"))
        .willReturn(okJson(AUTHORIZED_BODY)));

    String senderJwt   = registerAndLogin("emailsender@test.com", "password123");
    String receiverJwt = registerAndLogin("emailreceiver@test.com", "password123");
    UUID sourceWalletId = getWalletId(senderJwt);
    UUID targetWalletId = getWalletId(receiverJwt);
    jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", sourceWalletId);

    TransferByEmailRequest req = new TransferByEmailRequest("emailreceiver@test.com", new BigDecimal("40.00"));

    ResponseEntity<Map> response = restTemplate.exchange(
        "/api/v1/transfers/email", HttpMethod.POST, withAuth(req, senderJwt), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    BigDecimal sourceBalance = jdbcTemplate.queryForObject(
        "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, sourceWalletId);
    BigDecimal targetBalance = jdbcTemplate.queryForObject(
        "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, targetWalletId);
    assertThat(sourceBalance).isEqualByComparingTo("60.0000");
    assertThat(targetBalance).isEqualByComparingTo("40.0000");
}

@Test
void transferByEmail_selfTransfer_returns422() {
    wireMock.stubFor(get(urlEqualTo("/authorize"))
        .willReturn(okJson(AUTHORIZED_BODY)));

    String jwt = registerAndLogin("selfsender@test.com", "password123");
    TransferByEmailRequest req = new TransferByEmailRequest("selfsender@test.com", new BigDecimal("10.00"));

    ResponseEntity<Map> response = restTemplate.exchange(
        "/api/v1/transfers/email", HttpMethod.POST, withAuth(req, jwt), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
}
```

- [ ] **Step 2: Run TransferIT**

```bash
cd backend && ./mvnw test -Dtest=TransferIT -q
```

Expected: BUILD SUCCESS, all 9 tests pass (7 existing + 2 new).

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/liffeypay/liffeypay/integration/TransferIT.java
git commit -m "test: add transferByEmail integration tests"
```

---

## Final Verification

- [ ] **Run the full test suite**

```bash
cd backend && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD (SUCCESS|FAILURE)"
```

Expected: BUILD SUCCESS. All previous 95 tests still pass plus the 8 new ones = **103 total**.

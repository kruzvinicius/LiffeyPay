# LiffeyPay — Missing Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 19 missing tests across 6 new files — 3 for HTTP adapter services (WireMock, no Spring context) and 3 for JPA repositories (`@DataJpaTest` + Testcontainers Postgres).

**Architecture:** HTTP service tests instantiate the class under test directly with a WireMock URL — no Spring context, sub-second startup. Repository tests share a single Testcontainers Postgres container via an abstract `RepositoryTestBase`, following the same singleton pattern as `IntegrationTestBase`. `@AutoConfigureTestDatabase(replace = NONE)` forces `@DataJpaTest` to use the real Postgres; Flyway runs V1–V3 migrations before any test.

**Tech Stack:** Spring Boot 3.4.3, JUnit 5, WireMock 3.9.1 (`WireMockExtension`), Testcontainers (`PostgreSQLContainer`), AssertJ, `TestEntityManager`.

---

## File Map

- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/RepositoryTestBase.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/TransactionRepositoryTest.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/WalletRepositoryTest.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/UserRepositoryTest.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/service/HttpNotificationServiceTest.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/service/HttpAuthorizationServiceTest.java`

---

## Task 1 — RepositoryTestBase

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/RepositoryTestBase.java`

- [ ] **Step 1: Create RepositoryTestBase**

```java
// backend/src/test/java/com/liffeypay/liffeypay/repository/RepositoryTestBase.java
package com.liffeypay.liffeypay.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class RepositoryTestBase {

    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && ./mvnw test-compile -q
```

Expected: BUILD SUCCESS.

---

## Task 2 — TransactionRepositoryTest

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/TransactionRepositoryTest.java`

- [ ] **Step 1: Create TransactionRepositoryTest**

```java
// backend/src/test/java/com/liffeypay/liffeypay/repository/TransactionRepositoryTest.java
package com.liffeypay.liffeypay.repository;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TransactionRepositoryTest extends RepositoryTestBase {

    @Autowired TestEntityManager em;
    @Autowired TransactionRepository repo;

    private Wallet walletA;
    private Wallet walletB;

    @BeforeEach
    void setUp() {
        User userA = em.persist(User.builder()
            .fullName("Alice").email("alice@repo.test")
            .documentNumber("11100011100").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
        User userB = em.persist(User.builder()
            .fullName("Bob").email("bob@repo.test")
            .documentNumber("22200022200").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
        walletA = em.persist(Wallet.builder().user(userA).currency("EUR").build());
        walletB = em.persist(Wallet.builder().user(userB).currency("EUR").build());
        em.flush();
    }

    @Test
    void findByIdempotencyKey_exists_returnsTransaction() {
        em.persist(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("10.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).idempotencyKey("key-001").build());
        em.flush();

        Optional<Transaction> result = repo.findByIdempotencyKey("key-001");

        assertThat(result).isPresent();
        assertThat(result.get().getIdempotencyKey()).isEqualTo("key-001");
    }

    @Test
    void findByIdempotencyKey_notFound_returnsEmpty() {
        Optional<Transaction> result = repo.findByIdempotencyKey("non-existent-key");

        assertThat(result).isEmpty();
    }

    @Test
    void findAllBySourceOrTarget_returnsBothDirections() {
        em.persist(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("20.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        em.persist(Transaction.builder()
            .sourceWallet(walletB).targetWallet(walletA)
            .amount(new BigDecimal("10.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        em.flush();

        Page<Transaction> result = repo.findAllBySourceWalletIdOrTargetWalletId(
            walletA.getId(), walletA.getId(), PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findAllBySourceOrTarget_pagination_respectsPageSize() {
        for (int i = 0; i < 3; i++) {
            em.persist(Transaction.builder()
                .sourceWallet(walletA).targetWallet(walletB)
                .amount(new BigDecimal("5.0000")).currency("EUR")
                .status(TransactionStatus.COMPLETED).build());
        }
        em.flush();

        Page<Transaction> result = repo.findAllBySourceWalletIdOrTargetWalletId(
            walletA.getId(), walletA.getId(), PageRequest.of(0, 2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    void findAllBySourceOrTarget_ordering_latestFirst() throws InterruptedException {
        Transaction tx1 = em.persistAndFlush(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("1.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        Thread.sleep(50);
        Transaction tx3 = em.persistAndFlush(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("3.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        em.clear();

        Page<Transaction> result = repo.findAllBySourceWalletIdOrTargetWalletId(
            walletA.getId(), walletA.getId(),
            PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(result.getContent().get(0).getId()).isEqualTo(tx3.getId());
        assertThat(result.getContent().get(1).getId()).isEqualTo(tx1.getId());
    }
}
```

- [ ] **Step 2: Run TransactionRepositoryTest**

```bash
cd backend && ./mvnw test -Dtest=TransactionRepositoryTest -q
```

Expected: BUILD SUCCESS, 5 tests pass.

> **If tests fail with "relation does not exist":** Flyway did not run in the `@DataJpaTest` slice. Fix: add `@ImportAutoConfiguration(org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class)` to `RepositoryTestBase`.

---

## Task 3 — WalletRepositoryTest

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/WalletRepositoryTest.java`

- [ ] **Step 1: Create WalletRepositoryTest**

```java
// backend/src/test/java/com/liffeypay/liffeypay/repository/WalletRepositoryTest.java
package com.liffeypay.liffeypay.repository;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class WalletRepositoryTest extends RepositoryTestBase {

    @Autowired TestEntityManager em;
    @Autowired WalletRepository repo;

    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        user = em.persist(User.builder()
            .fullName("Owner").email("owner@wallet.test")
            .documentNumber("33300033300").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
        wallet = em.persist(Wallet.builder().user(user).currency("EUR").build());
        em.flush();
    }

    @Test
    void findByUser_Id_found() {
        Optional<Wallet> result = repo.findByUser_Id(user.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wallet.getId());
    }

    @Test
    void findByUser_Id_notFound() {
        Optional<Wallet> result = repo.findByUser_Id(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void findByUserEmail_found() {
        Optional<Wallet> result = repo.findByUserEmail("owner@wallet.test");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wallet.getId());
        assertThat(result.get().getUser().getEmail()).isEqualTo("owner@wallet.test");
    }

    @Test
    void findByIdWithLock_found() {
        Optional<Wallet> result = repo.findByIdWithLock(wallet.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wallet.getId());
        assertThat(result.get().getCurrency()).isEqualTo("EUR");
    }
}
```

- [ ] **Step 2: Run WalletRepositoryTest**

```bash
cd backend && ./mvnw test -Dtest=WalletRepositoryTest -q
```

Expected: BUILD SUCCESS, 4 tests pass.

---

## Task 4 — UserRepositoryTest

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/repository/UserRepositoryTest.java`

- [ ] **Step 1: Create UserRepositoryTest**

```java
// backend/src/test/java/com/liffeypay/liffeypay/repository/UserRepositoryTest.java
package com.liffeypay.liffeypay.repository;

import com.liffeypay.liffeypay.domain.model.User;
import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserRepositoryTest extends RepositoryTestBase {

    @Autowired TestEntityManager em;
    @Autowired UserRepository repo;

    private User user;

    @BeforeEach
    void setUp() {
        user = em.persistAndFlush(User.builder()
            .fullName("Test User").email("user@user.test")
            .documentNumber("44400044400").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
    }

    @Test
    void findByEmail_found() {
        Optional<User> result = repo.findByEmail("user@user.test");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(user.getId());
    }

    @Test
    void findByEmail_notFound() {
        Optional<User> result = repo.findByEmail("nobody@user.test");

        assertThat(result).isEmpty();
    }

    @Test
    void existsByDocumentNumber_trueAndFalse() {
        assertThat(repo.existsByDocumentNumber("44400044400")).isTrue();
        assertThat(repo.existsByDocumentNumber("99999999999")).isFalse();
    }
}
```

- [ ] **Step 2: Run all repository tests**

```bash
cd backend && ./mvnw test -Dtest="TransactionRepositoryTest+WalletRepositoryTest+UserRepositoryTest" -q
```

Expected: BUILD SUCCESS, 12 tests pass (5 + 4 + 3).

---

## Task 5 — HttpNotificationServiceTest

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/service/HttpNotificationServiceTest.java`

- [ ] **Step 1: Create HttpNotificationServiceTest**

```java
// backend/src/test/java/com/liffeypay/liffeypay/service/HttpNotificationServiceTest.java
package com.liffeypay.liffeypay.service;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatNoException;

class HttpNotificationServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private HttpNotificationService service;

    @BeforeEach
    void setUp() {
        service = new HttpNotificationService(wm.getRuntimeInfo().getHttpBaseUrl() + "/notify");
    }

    private TransferCompletedEvent event() {
        return new TransferCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("100.00"), "EUR"
        );
    }

    @Test
    void notify_success_noExceptionThrown() {
        wm.stubFor(post(urlEqualTo("/notify")).willReturn(aResponse().withStatus(200)));

        assertThatNoException().isThrownBy(() -> service.notify(event()));
    }

    @Test
    void notify_serverError_swallowsSilently() {
        wm.stubFor(post(urlEqualTo("/notify")).willReturn(aResponse().withStatus(500)));

        assertThatNoException().isThrownBy(() -> service.notify(event()));
    }

    @Test
    void notify_connectionReset_swallowsSilently() {
        wm.stubFor(post(urlEqualTo("/notify"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatNoException().isThrownBy(() -> service.notify(event()));
    }
}
```

- [ ] **Step 2: Run HttpNotificationServiceTest**

```bash
cd backend && ./mvnw test -Dtest=HttpNotificationServiceTest -q
```

Expected: BUILD SUCCESS, 3 tests pass.

---

## Task 6 — HttpAuthorizationServiceTest

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/service/HttpAuthorizationServiceTest.java`

- [ ] **Step 1: Create HttpAuthorizationServiceTest**

```java
// backend/src/test/java/com/liffeypay/liffeypay/service/HttpAuthorizationServiceTest.java
package com.liffeypay.liffeypay.service;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.util.UUID;

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

    private TransferRequest request() {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50.00"));
    }

    @Test
    void authorize_authorized_noExceptionThrown() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"AUTHORIZED\"}")));

        assertThatNoException().isThrownBy(() -> service.authorize(request()));
    }

    @Test
    void authorize_denied_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"DENIED\"}")));

        assertThatThrownBy(() -> service.authorize(request()))
            .isInstanceOf(TransferNotAuthorizedException.class);
    }

    @Test
    void authorize_serverError_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.authorize(request()))
            .isInstanceOf(TransferNotAuthorizedException.class);
    }

    @Test
    void authorize_connectionReset_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> service.authorize(request()))
            .isInstanceOf(TransferNotAuthorizedException.class);
    }
}
```

- [ ] **Step 2: Run HttpAuthorizationServiceTest**

```bash
cd backend && ./mvnw test -Dtest=HttpAuthorizationServiceTest -q
```

Expected: BUILD SUCCESS, 4 tests pass.

---

## Final Verification

- [ ] **Run the full test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS. All previous tests still pass plus the 19 new ones.

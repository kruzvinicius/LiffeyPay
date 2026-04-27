# LiffeyPay — Integration Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add full-stack integration tests using a real PostgreSQL database (Testcontainers) and WireMock for external HTTP services, covering all main API endpoints.

**Architecture:** A shared abstract `IntegrationTestBase` starts one PostgreSQL container and one WireMock server for the entire suite via static fields. Each IT class extends the base and runs against the real HTTP server (`@SpringBootTest(RANDOM_PORT)`). Data is cleaned with `DELETE` statements in `@BeforeEach`.

**Tech Stack:** Spring Boot 3.4.3, JUnit 5, Testcontainers (BOM managed), `org.wiremock:wiremock:3.9.1`, TestRestTemplate.

---

## File Map

- Modify: `backend/pom.xml`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/IntegrationTestBase.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/AuthIT.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/WalletIT.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/TransferIT.java`
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/TransactionHistoryIT.java`

---

## Task 1 — Add Dependencies

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Add testcontainers and wiremock to pom.xml**

Inside the `<dependencies>` block, add after the existing test dependencies:

```xml
<!-- Testcontainers (version managed by Spring Boot BOM) -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<!-- WireMock -->
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock</artifactId>
    <version>3.9.1</version>
    <scope>test</scope>
</dependency>
```

Also add Surefire configuration inside the `<build><plugins>` block so that `*IT` classes are picked up by `./mvnw test`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*Test.java</include>
            <include>**/*Tests.java</include>
            <include>**/*IT.java</include>
        </includes>
    </configuration>
</plugin>
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && ./mvnw compile test-compile -q
```

Expected: BUILD SUCCESS (no download errors — if wiremock `3.9.1` is unavailable, check `https://search.maven.org/artifact/org.wiremock/wiremock` and use the latest available version).

- [ ] **Step 3: Commit**

```bash
git add backend/pom.xml
git commit -m "test: add testcontainers and wiremock dependencies"
```

---

## Task 2 — IntegrationTestBase

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/IntegrationTestBase.java`

- [ ] **Step 1: Create IntegrationTestBase**

```java
// backend/src/test/java/com/liffeypay/liffeypay/integration/IntegrationTestBase.java
package com.liffeypay.liffeypay.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.dto.LoginRequest;
import com.liffeypay.liffeypay.dto.RegisterUserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
public abstract class IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    static final WireMockServer wireMock =
        new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.authorization.url",
            () -> wireMock.baseUrl() + "/authorize");
        registry.add("app.notification.url",
            () -> wireMock.baseUrl() + "/notify");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void cleanUp() {
        wireMock.resetAll();
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM wallets");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @SuppressWarnings("unchecked")
    protected String registerAndLogin(String email, String password) {
        RegisterUserRequest reg = new RegisterUserRequest(
            "Test User", email,
            UUID.randomUUID().toString().replace("-", "").substring(0, 11),
            password, UserType.INDIVIDUAL, "EUR"
        );
        restTemplate.postForEntity("/api/v1/users", reg, Map.class);

        var response = restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, password), Map.class);
        var data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        return (String) data.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    protected String registerAndLoginAsMerchant(String email, String password) {
        RegisterUserRequest reg = new RegisterUserRequest(
            "Merchant", email,
            UUID.randomUUID().toString().replace("-", "").substring(0, 11),
            password, UserType.MERCHANT, "EUR"
        );
        restTemplate.postForEntity("/api/v1/users", reg, Map.class);

        var response = restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, password), Map.class);
        var data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        return (String) data.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    protected UUID getWalletId(String jwt) {
        var response = restTemplate.exchange(
            "/api/v1/wallets/me", HttpMethod.GET, withAuth(jwt), Map.class);
        var data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        return UUID.fromString((String) data.get("walletId"));
    }

    protected HttpEntity<?> withAuth(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return new HttpEntity<>(headers);
    }

    protected <T> HttpEntity<T> withAuth(T body, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return new HttpEntity<>(body, headers);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && ./mvnw compile test-compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/liffeypay/liffeypay/integration/IntegrationTestBase.java
git commit -m "test: add IntegrationTestBase with Testcontainers and WireMock"
```

---

## Task 3 — AuthIT

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/AuthIT.java`

- [ ] **Step 1: Create AuthIT**

```java
// backend/src/test/java/com/liffeypay/liffeypay/integration/AuthIT.java
package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.dto.LoginRequest;
import com.liffeypay.liffeypay.dto.RegisterUserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void register_createsUserAndWallet() {
        RegisterUserRequest req = new RegisterUserRequest(
            "Vinicius Cruz", "vinicius@test.com", "12345678901",
            "password123", UserType.INDIVIDUAL, "EUR"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/users", req, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isNotNull();
        Long walletCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM wallets WHERE user_id = " +
            "(SELECT id FROM users WHERE email = ?)",
            Long.class, "vinicius@test.com");
        assertThat(walletCount).isEqualTo(1L);
    }

    @Test
    void login_validCredentials_returnsJwt() {
        String jwt = registerAndLogin("user@test.com", "password123");
        assertThat(jwt).isNotBlank();
    }

    @Test
    void login_wrongPassword_returns401() {
        registerAndLogin("user@test.com", "password123");

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/auth/login",
            new LoginRequest("user@test.com", "wrongpass"),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void register_duplicateEmail_returns400() {
        RegisterUserRequest first = new RegisterUserRequest(
            "User One", "dup@test.com", "11122233344",
            "password123", UserType.INDIVIDUAL, "EUR"
        );
        restTemplate.postForEntity("/api/v1/users", first, Map.class);

        RegisterUserRequest second = new RegisterUserRequest(
            "User Two", "dup@test.com", "99988877766",
            "password123", UserType.INDIVIDUAL, "EUR"
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/users", second, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run AuthIT and verify RED (container not up yet — first run will download Docker image)**

```bash
cd backend && ./mvnw test -Dtest=AuthIT
```

Expected: BUILD SUCCESS, 4 tests pass. The first run may take ~30s to pull the PostgreSQL Docker image.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/liffeypay/liffeypay/integration/AuthIT.java
git commit -m "test: add AuthIT integration tests"
```

---

## Task 4 — WalletIT

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/WalletIT.java`

- [ ] **Step 1: Create WalletIT**

```java
// backend/src/test/java/com/liffeypay/liffeypay/integration/WalletIT.java
package com.liffeypay.liffeypay.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletIT extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void getMyWallet_authenticated_returnsWallet() {
        String jwt = registerAndLogin("wallet@test.com", "password123");

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me", org.springframework.http.HttpMethod.GET,
            withAuth(jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isNotNull();
        assertThat(data.get("currency")).isEqualTo("EUR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getById_owner_returnsWallet() {
        String jwt = registerAndLogin("owner@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + walletId,
            org.springframework.http.HttpMethod.GET,
            withAuth(jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isEqualTo(walletId.toString());
    }

    @Test
    void getById_nonOwner_returns404() {
        String ownerJwt  = registerAndLogin("owner2@test.com", "password123");
        String hackerJwt = registerAndLogin("hacker@test.com", "password123");
        UUID ownerWalletId = getWalletId(ownerJwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + ownerWalletId,
            org.springframework.http.HttpMethod.GET,
            withAuth(hackerJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getMyWallet_noToken_returns401() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            "/api/v1/wallets/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run WalletIT**

```bash
cd backend && ./mvnw test -Dtest=WalletIT
```

Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/liffeypay/liffeypay/integration/WalletIT.java
git commit -m "test: add WalletIT integration tests"
```

---

## Task 5 — TransferIT

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/TransferIT.java`

- [ ] **Step 1: Create TransferIT**

```java
// backend/src/test/java/com/liffeypay/liffeypay/integration/TransferIT.java
package com.liffeypay.liffeypay.integration;

import com.github.tomakehurst.wiremock.http.Fault;
import com.liffeypay.liffeypay.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransferIT extends IntegrationTestBase {

    private static final String AUTHORIZED_BODY =
        "{\"status\":\"AUTHORIZED\"}";

    @Test
    @SuppressWarnings("unchecked")
    void transfer_happyPath_balancesUpdatedInDb() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String senderJwt   = registerAndLogin("sender@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);

        // Seed sender balance directly in DB
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?",
            sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("30.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST,
            withAuth(req, senderJwt),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        BigDecimal sourceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, sourceWalletId);
        BigDecimal targetBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, targetWalletId);
        assertThat(sourceBalance).isEqualByComparingTo("70.0000");
        assertThat(targetBalance).isEqualByComparingTo("30.0000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transfer_idempotency_secondCallReturnsCachedResult() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String senderJwt   = registerAndLogin("sender2@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver2@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?",
            sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("20.00"));

        org.springframework.http.HttpHeaders headers =
            new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(senderJwt);
        headers.set("Idempotency-Key", "unique-key-abc123");
        var entity = new org.springframework.http.HttpEntity<>(req, headers);

        ResponseEntity<Map> first = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST, entity, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second call with same key — balance must NOT be deducted again
        ResponseEntity<Map> second = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST, entity, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> firstData  = (Map<String, Object>)
            ((Map<String, Object>) first.getBody()).get("data");
        Map<String, Object> secondData = (Map<String, Object>)
            ((Map<String, Object>) second.getBody()).get("data");
        assertThat(firstData.get("id")).isEqualTo(secondData.get("id"));

        BigDecimal sourceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, sourceWalletId);
        assertThat(sourceBalance).isEqualByComparingTo("80.0000");
    }

    @Test
    void transfer_merchantSource_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String merchantJwt  = registerAndLoginAsMerchant("merchant@test.com", "password123");
        String receiverJwt  = registerAndLogin("rcv@test.com", "password123");
        UUID merchantWallet = getWalletId(merchantJwt);
        UUID targetWallet   = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?",
            merchantWallet);

        TransferRequest req = new TransferRequest(
            merchantWallet, targetWallet, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST,
            withAuth(req, merchantJwt), Map.class);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_insufficientFunds_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String senderJwt   = registerAndLogin("broke@test.com", "password123");
        String receiverJwt = registerAndLogin("rcv2@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        // balance stays at 0.0000

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("50.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST,
            withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_authorizerDenies_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"DENIED\"}")));

        String senderJwt   = registerAndLogin("sender3@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver3@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?",
            sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST,
            withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_authorizerDown_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        String senderJwt   = registerAndLogin("sender4@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver4@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?",
            sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST,
            withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_notificationFails_transferSucceeds() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));
        wireMock.stubFor(post(urlEqualTo("/notify"))
            .willReturn(aResponse().withStatus(500)));

        String senderJwt   = registerAndLogin("sender5@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver5@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?",
            sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST,
            withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
```

- [ ] **Step 2: Run TransferIT**

```bash
cd backend && ./mvnw test -Dtest=TransferIT
```

Expected: BUILD SUCCESS, 7 tests pass.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/liffeypay/liffeypay/integration/TransferIT.java
git commit -m "test: add TransferIT integration tests"
```

---

## Task 6 — TransactionHistoryIT

**Files:**
- Create: `backend/src/test/java/com/liffeypay/liffeypay/integration/TransactionHistoryIT.java`

- [ ] **Step 1: Create TransactionHistoryIT**

```java
// backend/src/test/java/com/liffeypay/liffeypay/integration/TransactionHistoryIT.java
package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionHistoryIT extends IntegrationTestBase {

    private static final String AUTHORIZED_BODY = "{\"status\":\"AUTHORIZED\"}";

    private void executeTransfer(UUID sourceWalletId, UUID targetWalletId,
                                 BigDecimal amount, String jwt) {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));
        TransferRequest req = new TransferRequest(sourceWalletId, targetWalletId, amount);
        restTemplate.exchange(
            "/api/v1/transfers",
            org.springframework.http.HttpMethod.POST,
            withAuth(req, jwt), Map.class);
        wireMock.resetAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_returnsSentAndReceived() {
        String aliceJwt = registerAndLogin("alice@test.com", "password123");
        String bobJwt   = registerAndLogin("bob@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);
        UUID bobWallet   = getWalletId(bobJwt);

        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", aliceWallet);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", bobWallet);

        executeTransfer(aliceWallet, bobWallet, new BigDecimal("20.00"), aliceJwt); // alice SENT
        executeTransfer(bobWallet, aliceWallet, new BigDecimal("10.00"), bobJwt);   // alice RECEIVED

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + aliceWallet + "/transactions",
            org.springframework.http.HttpMethod.GET,
            withAuth(aliceJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        List<Map<String, Object>> content =
            (List<Map<String, Object>>) data.get("content");
        assertThat(content).hasSize(2);

        List<String> types = content.stream()
            .map(tx -> (String) tx.get("type"))
            .toList();
        assertThat(types).containsExactlyInAnyOrder("SENT", "RECEIVED");
    }

    @Test
    void getHistory_nonOwner_returns404() {
        String ownerJwt  = registerAndLogin("owner3@test.com", "password123");
        String hackerJwt = registerAndLogin("hacker2@test.com", "password123");
        UUID ownerWallet = getWalletId(ownerJwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + ownerWallet + "/transactions",
            org.springframework.http.HttpMethod.GET,
            withAuth(hackerJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_pagination_respectsPageSize() {
        String aliceJwt = registerAndLogin("alice2@test.com", "password123");
        String bobJwt   = registerAndLogin("bob2@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);
        UUID bobWallet   = getWalletId(bobJwt);

        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", aliceWallet);
        executeTransfer(aliceWallet, bobWallet, new BigDecimal("10.00"), aliceJwt);

        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", aliceWallet);
        executeTransfer(aliceWallet, bobWallet, new BigDecimal("5.00"), aliceJwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + aliceWallet + "/transactions?page=0&size=1",
            org.springframework.http.HttpMethod.GET,
            withAuth(aliceJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        List<?> content = (List<?>) data.get("content");
        assertThat(content).hasSize(1);
        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(2L);
    }
}
```

- [ ] **Step 2: Run TransactionHistoryIT**

```bash
cd backend && ./mvnw test -Dtest=TransactionHistoryIT
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 3: Run all tests to verify nothing broke**

```bash
cd backend && ./mvnw test
```

Expected: BUILD SUCCESS, all tests pass (46 unit + 18 integration = 64 total).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/liffeypay/liffeypay/integration/TransactionHistoryIT.java
git commit -m "test: add TransactionHistoryIT integration tests"
git push
```

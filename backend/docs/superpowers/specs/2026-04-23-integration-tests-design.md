# LiffeyPay — Integration Tests Design Spec
**Date:** 2026-04-23
**Status:** Approved

## Overview

Full-stack integration tests using a real PostgreSQL database (Testcontainers) and WireMock for external HTTP services. Tests cover all main API endpoints: auth, wallet, transfer, and transaction history.

---

## Infrastructure

### New Dependencies (`pom.xml`, test scope)
- `org.testcontainers:testcontainers` (BOM managed)
- `org.testcontainers:postgresql`
- `org.testcontainers:junit-jupiter`
- `com.github.tomakehurst:wiremock-spring-boot`

### `IntegrationTestBase` (abstract)

All IT classes extend this. Responsibilities:
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` — starts real embedded server
- `@Testcontainers` + `static @Container PostgreSQLContainer<?>` — one container for the entire suite
- `@DynamicPropertySource` — injects container URL/credentials before Spring context starts
- `TestRestTemplate` — injected for real HTTP calls
- `JdbcTemplate` — used in `@BeforeEach` for data cleanup
- `@BeforeEach` — deletes all rows in FK-safe order: `transactions → wallets → users`
- `registerAndLogin(String email, String password)` helper — calls `POST /api/v1/users` then `POST /api/v1/auth/login`, returns JWT string
- `authHeader(String jwt)` helper — returns `HttpHeaders` with `Authorization: Bearer <jwt>`

### WireMock

`@EnableWireMock` on `IntegrationTestBase`. Each test configures stubs explicitly via `@InjectWireMock WireMockServer wireMock`. The WireMock base URL is exposed as `${wiremock.server.baseUrl}` — `application.properties` references it directly:

```properties
app.authorization.url=${wiremock.server.baseUrl}/authorize
app.notification.url=${wiremock.server.baseUrl}/notify
```

### Data Cleanup

```sql
DELETE FROM transactions;
DELETE FROM wallets;
DELETE FROM users;
```

`DELETE` (not `TRUNCATE`) to avoid sequence resets and Flyway constraint conflicts. Order respects FK constraints.

---

## Test Classes

### `AuthIT`

**Endpoint coverage:** `POST /api/v1/users`, `POST /api/v1/auth/login`

| Test | Expected |
|------|----------|
| `register_createsUserAndWallet` | 201, wallet exists in DB |
| `login_validCredentials_returnsJwt` | 200, token non-null |
| `login_wrongPassword_returns401` | 401 |
| `register_duplicateEmail_returns409` | 409 |

---

### `WalletIT`

**Endpoint coverage:** `GET /api/v1/wallets/me`, `GET /api/v1/wallets/{id}`

| Test | Expected |
|------|----------|
| `getMyWallet_authenticated_returnsWallet` | 200, correct balance/currency |
| `getById_owner_returnsWallet` | 200 |
| `getById_nonOwner_returns404` | 404 |
| `getMyWallet_noToken_returns401` | 401 |

---

### `TransferIT`

**Endpoint coverage:** `POST /api/v1/transfers`

WireMock controls authorization responses per test.

| Test | WireMock stub | Expected |
|------|--------------|----------|
| `transfer_happyPath_balancesUpdatedInDb` | `{"status":"AUTHORIZED"}` | 201, balances mutated |
| `transfer_idempotency_secondCallReturnsCached` | `{"status":"AUTHORIZED"}` | 201 (same body, no re-execution) |
| `transfer_merchantSource_returns422` | `{"status":"AUTHORIZED"}` | 422 |
| `transfer_insufficientFunds_returns422` | `{"status":"AUTHORIZED"}` | 422 |
| `transfer_authorizerDenies_returns422` | `{"status":"DENIED"}` | 422 |
| `transfer_authorizerDown_returns422` | `withFault(CONNECTION_RESET_BY_PEER)` | 422 |
| `transfer_notificationFails_transferSucceeds` | authorized + notify 500 | 201 |

---

### `TransactionHistoryIT`

**Endpoint coverage:** `GET /api/v1/wallets/{id}/transactions`

| Test | Expected |
|------|----------|
| `getHistory_returnsSentAndReceived` | 200, type=SENT and RECEIVED correct |
| `getHistory_nonOwner_returns404` | 404 |
| `getHistory_pagination_respectsPageSize` | `size=1` returns 1 item, `totalElements=2` |

---

## Test Profile

`src/test/resources/application-integration.properties`:
```properties
spring.flyway.enabled=true
```

Testcontainers provides the datasource URL dynamically — no static DB config needed in the test profile.

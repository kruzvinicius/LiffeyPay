# LiffeyPay — Missing Tests Design Spec
**Date:** 2026-04-24
**Status:** Approved

## Overview

Add missing unit and slice tests to close coverage gaps left after the features and integration test plans. Three new test files for HTTP adapter services (WireMock, no Spring context) and three for repositories (`@DataJpaTest` + Testcontainers Postgres).

---

## Approach

**HTTP service tests:** Standalone — services instantiated directly with `new Http*Service(wireMock.baseUrl(), ...)`. No Spring context, no `@SpringBootTest`. WireMock via `@RegisterExtension`. Fast and focused.

**Repository tests:** `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers Postgres. One class per repository. Fixtures via `TestEntityManager`.

The `@Value`-wiring of `app.authorization.url` / `app.notification.url` is already validated indirectly by `TransferIT` and `TransactionHistoryIT`, so standalone instantiation is sufficient.

---

## New Files

```
src/test/java/com/liffeypay/liffeypay/
  service/
    HttpNotificationServiceTest.java
    HttpAuthorizationServiceTest.java
  repository/
    RepositoryTestBase.java        ← abstract, holds static Postgres container
    TransactionRepositoryTest.java
    WalletRepositoryTest.java
    UserRepositoryTest.java
```

---

## HTTP Service Tests

### Shared Setup

Both tests use:
```java
@RegisterExtension
static WireMockExtension wm = WireMockExtension.newInstance().build();
```
Service instance created in `@BeforeEach` using `wm.baseUrl()`.

### HttpNotificationServiceTest

| Test | WireMock stub | Expected |
|------|--------------|----------|
| `notify_success_noExceptionThrown` | POST `/notify` → 200 | no exception |
| `notify_serverError_swallowsSilently` | POST `/notify` → 500 | no exception |
| `notify_connectionReset_swallowsSilently` | `Fault.CONNECTION_RESET_BY_PEER` | no exception |

Setup: `new HttpNotificationService(wm.baseUrl() + "/notify")`

### HttpAuthorizationServiceTest

| Test | WireMock stub | Expected |
|------|--------------|----------|
| `authorize_authorized_noExceptionThrown` | GET `/authorize` → `{"status":"AUTHORIZED"}` | no exception |
| `authorize_denied_throwsTransferNotAuthorizedException` | GET `/authorize` → `{"status":"DENIED"}` | throws `TransferNotAuthorizedException` |
| `authorize_serverError_throwsTransferNotAuthorizedException` | GET `/authorize` → 500 | throws `TransferNotAuthorizedException` |
| `authorize_connectionReset_throwsTransferNotAuthorizedException` | `Fault.CONNECTION_RESET_BY_PEER` | throws `TransferNotAuthorizedException` |

Setup: `new HttpAuthorizationService(wm.baseUrl() + "/authorize", 3000)`

---

## Repository Tests

### Shared Infrastructure

`RepositoryTestBase` is an abstract class that holds the static Postgres container, following the same pattern as `IntegrationTestBase`. All three repository test classes extend it.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
abstract class RepositoryTestBase {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) { ... }
}
```

One container starts for the entire `@DataJpaTest` suite. Fixtures via `@Autowired TestEntityManager em`.

### TransactionRepositoryTest

| Test | Description |
|------|-------------|
| `findByIdempotencyKey_exists_returnsTransaction` | Persists tx with key, finds it |
| `findByIdempotencyKey_notFound_returnsEmpty` | Random key returns `Optional.empty()` |
| `findAllBySourceOrTarget_returnsBothDirections` | Alice sent + received; query on Alice wallet returns both |
| `findAllBySourceOrTarget_pagination_respectsPageSize` | 3 txs, `size=2` → 2 results, `totalElements=3` |
| `findAllBySourceOrTarget_ordering_latestFirst` | `Sort.by("createdAt").descending()` returns in correct order |

### WalletRepositoryTest

| Test | Description |
|------|-------------|
| `findByUser_Id_found` | Persists user + wallet, finds by user UUID |
| `findByUser_Id_notFound` | Random UUID returns `Optional.empty()` |
| `findByUserEmail_found` | Finds wallet by user email (JOIN FETCH) |
| `findByIdWithLock_found` | Pessimistic lock query returns correct wallet |

### UserRepositoryTest

| Test | Description |
|------|-------------|
| `findByEmail_found` | Persists user, finds by email |
| `findByEmail_notFound` | Unknown email returns `Optional.empty()` |
| `existsByDocumentNumber_trueAndFalse` | Asserts true for existing doc, false for unknown |

---

## Notes

- `AuthIT.register_duplicateEmail_returns400` is **correct** — `UserService` throws `BusinessException` (400) before hitting the DB constraint. The spec was wrong to say 409. No change needed.
- `@DataJpaTest` slice does NOT load `@Service` or `@Controller` beans — only JPA repositories, `TestEntityManager`, and the Flyway migrations.
- The static Postgres container in `RepositoryTestBase` is shared across all three repository test classes — one container for the whole `@DataJpaTest` suite, not three separate ones.

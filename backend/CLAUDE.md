# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run the application
./mvnw spring-boot:run

# Build
./mvnw clean install

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Run a single test method
./mvnw test -Dtest=ClassName#methodName

# Package (skip tests)
./mvnw clean package -DskipTests
```

## Stack

- **Spring Boot 3.4.3**, Java 21
- **Spring Web MVC** — servlet-based REST/MVC controllers
- **Spring Security** — authentication and authorization
- **Spring Data JPA** + **PostgreSQL** — persistence
- **Spring Validation** — bean validation via `@Valid`
- **Lombok** — annotation-based boilerplate reduction (configured as annotation processor)

## Behavioral Skill: Get Shit Done (GSD)
- **Focus on Impact:** Prioritize functional code over perfect abstractions.
- **No Over-Engineering:** Do not add libraries or complex patterns unless strictly necessary for the current task.
- **Biased for Action:** If a task is clear, execute it immediately. If it's ambiguous, ask one (and only one) clarifying question.
- **Finish What You Start:** Every session must end with a buildable, testable piece of code.

## Architecture

Layered structure under `com.liffeypay.liffeypay`:

```
config/        — SecurityConfig (Spring Security, PasswordEncoder)
controller/    — REST controllers (@RestController, /api/v1/*)
domain/
  model/       — JPA entities (User, Wallet, Transaction) + enums
  repository/  — Spring Data JPA interfaces
dto/           — Java Records: TransferRequest, TransferResponse, ApiResponse<T>
exception/     — BusinessException hierarchy + GlobalExceptionHandler (@RestControllerAdvice)
service/       — TransferService (business logic)
```

### Key design decisions

**Monetary values** — always `BigDecimal(precision=19, scale=4)`, never `double`.

**Concurrency / deadlock prevention** — `TransferService` acquires pessimistic write locks (`SELECT … FOR UPDATE`) on both wallets in **consistent UUID order** before any balance mutation. `Wallet` also carries a `@Version` field as a secondary optimistic-lock guard.

**Idempotency** — `POST /api/v1/transfers` accepts an optional `Idempotency-Key` header. The service first checks `transactions.idempotency_key` (unique index); if found, returns the cached result without re-executing. A `UNIQUE` constraint on the column acts as a database-level backstop for the race condition where two concurrent identical requests both pass the in-memory check.

**Exception hierarchy** — `BusinessException` (base, 400) → `InsufficientFundsException` (422), `DuplicateTransferException` (409), `ResourceNotFoundException` (404). `GlobalExceptionHandler` also catches `DataIntegrityViolationException` (409) and `MethodArgumentNotValidException` (400 with field-error map).

**Response envelope** — all endpoints return `ApiResponse<T>` (record with `success`, `data`, `message`, `timestamp`).

**Security** — stateless session, CSRF disabled, ready for JWT filter insertion (see TODO in `SecurityConfig`).

## Testing

Test starters available: `spring-boot-starter-webmvc-test` (MockMvc via `@WebMvcTest`), `spring-boot-starter-data-jpa-test` (`@DataJpaTest`), `spring-boot-starter-security-test`, `spring-boot-starter-validation-test`. Use slice tests for unit coverage and `@SpringBootTest` for integration tests.

## Behavioral Skill: Get Shit Done (GSD)
- **Focus on Impact:** Prioritize functional code over perfect abstractions.
- **No Over-Engineering:** Do not add libraries or complex patterns unless strictly necessary for the current task.
- **Biased for Action:** If a task is clear, execute it immediately. If it's ambiguous, ask one (and only one) clarifying question.
- **Finish What You Start:** Every session must end with a buildable, testable piece of code.

## Documentation Rules
- Only send to Obsidian what is 'Ready' or architectural decisions.
- PROHIBITED: Log drafts or intermediate tests.
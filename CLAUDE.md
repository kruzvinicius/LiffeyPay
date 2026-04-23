# LiffeyPay — Monorepo

## Structure

```
LiffeyPay/
├── backend/     — Spring Boot 3.4.3 / Java 21 REST API
├── frontend/    — (coming soon)
└── docker-compose.yml
```

## Backend

See `backend/CLAUDE.md` for full backend instructions.

```bash
cd backend

# Run
./mvnw spring-boot:run

# Build
./mvnw clean install

# Test
./mvnw test
```

## Docker (full stack)

```bash
docker-compose up
```

# Travel billing API

Flight, hotel, rail and Navan-fee transactions, plus invoices built as frozen snapshots of those transactions.

Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, plain JDBC.

## Requirements

- Java 21
- Docker (Docker Desktop on Mac/Windows) — running

## Run the API

```bash
./mvnw spring-boot:run
```

This starts Postgres from `docker-compose.yml` automatically, applies the Flyway migrations, and serves on `http://localhost:8080`.

```bash
curl -s localhost:8080/health
# {"status":"ok","database":"up"}
```

## Run the tests

```bash
./mvnw test
```

Tests start their own throwaway Postgres container (Testcontainers) and call the real HTTP API.

## Example requests

_To be filled in as each endpoint is built._

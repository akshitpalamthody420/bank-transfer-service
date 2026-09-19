# Bank Transfer Service

[![Build and test](https://github.com/akshitpalamthody420/bank-transfer-service/actions/workflows/ci.yml/badge.svg)](https://github.com/akshitpalamthody420/bank-transfer-service/actions/workflows/ci.yml)

A small Spring Boot API for creating accounts, moving money between them, and
reading transfer history. The interesting part is keeping balances correct when
a request fails or several requests arrive at once.

Built with Java 21, Spring Boot 3.5, PostgreSQL, Spring JDBC, Flyway, JUnit 5,
Testcontainers, Docker Compose, and GitHub Actions. There is no frontend.

This is a portfolio project with fictional GBP balances. It has no authentication
or account ownership checks, so it should not be exposed as a real banking API.
Compose binds its ports to localhost.

## Run with Docker

Install Docker with Compose, then:

```bash
git clone https://github.com/akshitpalamthody420/bank-transfer-service.git
cd bank-transfer-service
docker compose up --build --wait
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health

The first build downloads Maven dependencies. PostgreSQL data stays in a named
volume. `docker compose down` stops the services; adding `-v` also deletes the
database. The default bank/bank credentials are for local development only.
Copy `.env.example` to `.env` to change the Compose password before the first run.
Changing that value does not change the password in an existing database volume.

## Run with Java and Maven

You need Java 21, Maven 3.9+, and PostgreSQL (the Docker setup uses PostgreSQL 17).

```bash
docker compose up -d db
mvn spring-boot:run
```

The defaults are `jdbc:postgresql://localhost:5432/bank`, user `bank`, password
`bank`. Override them with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.
Flyway applies the SQL migrations on startup; it does not drop existing data.

## Try the API

Create Alice's account:

```bash
curl -i http://localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","startingBalance":100.00}'
```

Create Bob's account with the same endpoint and a starting balance of 0. Replace
the two UUIDs below with the IDs returned by those requests:

```bash
curl -i http://localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: alice-to-bob-001' \
  -d '{"fromAccountId":"ALICE_UUID","toAccountId":"BOB_UUID","amount":25.00}'
```

A new transfer returns `201`. Sending that exact transfer again with the same key
returns `200` and the original transfer, without moving money again. Changing the
amount or either account while keeping the key returns `409`.

```bash
curl http://localhost:8080/api/accounts/ALICE_UUID
curl 'http://localhost:8080/api/accounts/ALICE_UUID/transactions?limit=20&offset=0'
```

Examples use a Bash-style shell. On Windows PowerShell, use `curl.exe` or the
Swagger UI if shell quoting gets in the way.

| Endpoint | Result |
| --- | --- |
| POST /api/accounts | 201, account body and Location header |
| GET /api/accounts/{id} | 200 with account details, or 404 |
| POST /api/transfers | 201 for new transfer; 200 for replay |
| GET /api/accounts/{id}/transactions | 200, newest transfers first |

History contains successful incoming and outgoing transfers. It does not contain
starting-balance entries or failed attempts. Each row includes both account IDs,
so callers can determine its direction. Pagination defaults to 20 rows, with
`limit` from 1 to 100 and nonnegative `offset`. Offset pages can shift while new
transfers are being added.

## Money and errors

Amounts are GBP only, positive for transfers and nonnegative for starting
balances. Use at most two decimal places and twelve integer digits. The maximum
supported account balance is 999999999999.99. Java uses `BigDecimal`; PostgreSQL
uses `NUMERIC`. No floating-point arithmetic is used for balances.

Errors use JSON Problem Details with an extra stable `code` field.

| HTTP status | Examples |
| --- | --- |
| 400 | Invalid JSON/UUID, missing key, invalid amount, same account |
| 404 | Account does not exist |
| 409 | Key reused with different details, destination balance limit |
| 422 | Insufficient funds |
| 503 | Database operation failed or lock wait could not complete |

Example:

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "The source account does not have enough funds.",
  "code": "INSUFFICIENT_FUNDS"
}
```

Transfer keys are case-sensitive, 1–128 characters, and may contain letters,
digits, dots, underscores, colons, and hyphens. A successful key is retained
indefinitely. A failed transaction does not reserve its key. After a timeout or
503, retry with the same key and payload: the previous attempt may have committed
even if its HTTP response was lost. Account creation is not idempotent.

## How a transfer works

```text
HTTP request
  -> TransferController: validate JSON and key
  -> TransferService: begin database transaction
     -> lock the idempotency key
     -> return previous result if already completed
     -> lock both accounts in UUID order
     -> check funds and destination limit
     -> debit source, credit destination, insert transfer
  -> commit
  -> return JSON
```

Spring's `@Transactional` wraps the service method. Both balance updates and the
transfer record use the same JDBC transaction. If recording the transfer fails
after the balances were updated, the transaction rolls everything back.

Row locks prevent two requests from spending the same available balance.
Ordering the locks avoids the usual deadlock when A sends to B while B sends to
A. A PostgreSQL transaction-scoped advisory lock serializes requests with the
same key; the full key also has a unique database constraint. The lock hash is
only for coordination, not identity: a hash collision delays unrelated requests
but does not treat them as duplicates.

The transaction uses READ COMMITTED so a request that waited for the key lock
can see the transfer committed by the earlier request. Locks release on commit
or rollback. Database lock waits are limited to five seconds. There is no
automatic server-side retry.

## Code layout

- `account/`: account request/response records, controller, service, repository.
- `transfer/`: transfer API, transaction boundary, history and idempotency SQL.
- `api/`: domain exceptions and JSON error mapping.
- `src/main/resources/db/migration/`: versioned schema and constraints.
- `src/test/java/dev/akshit/bank/TransferApiTest.java`: JUnit integration tests.
- `scripts/smoke.py`: real HTTP checks against the running Compose stack.

Spring JDBC keeps the important locking SQL visible. There is no ORM, message
broker, distributed transaction, or separate microservice.

## Tests and CI

```bash
mvn verify
```

By default, tests start a disposable PostgreSQL 17 container through Testcontainers.
Docker must be running; tests fail rather than silently skipping database checks.

The 14 JUnit tests cover account creation, validation, history/pagination, successful
transfers, insufficient funds, duplicate keys, changed payloads, balance limits,
rollback, concurrent duplicate requests, concurrent spending, opposite-direction
transfers, and OpenAPI/health endpoints.

The rollback test injects a repository failure after both real SQL balance
updates. It then reads the database outside the failed transaction and checks
that both balances and transfer history are unchanged. Concurrency tests use
separate threads and transactions released together by a latch.

If Docker is unavailable, point tests at a dedicated empty PostgreSQL database:

```bash
IT_DB_URL=jdbc:postgresql://localhost:5432/bank_test \
IT_DB_USER=bank IT_DB_PASSWORD=bank mvn verify
```

**Use a disposable test database. Tests truncate the accounts and transfers tables.**
The local fallback was verified with PostgreSQL 18; CI and Compose target 17.

GitHub Actions runs `mvn verify` on every push and pull request, uploads JUnit
reports, then builds and starts the Docker stack and runs the HTTP smoke script.
The container build skips tests because the preceding CI job runs them against
PostgreSQL. A Docker build alone is not a test run.

## Deliberate limits

This demonstrates transactions and API engineering, not a complete banking system.
There is no login, authorization, overdraft, currency conversion, external
payment processing, reversal workflow, rate limiting, or production audit ledger.
Only successful transfers are recorded. Starting balances are supplied when
accounts are created. Database backups and idempotency-key retention policies
would need separate work before any production use.

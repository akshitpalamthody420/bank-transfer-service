# Bank Transfer Service

A small Java 21 / Spring Boot REST API backed by PostgreSQL. This is a learning
project using GBP and fictional balances, not a service for real money.

## Milestones
1. Account creation and lookup, validation, and Flyway schema.
2. Atomic transfers, idempotency, and transaction history.
3. PostgreSQL integration tests, including rollback and concurrent transfers.
4. Docker Compose, GitHub Actions, OpenAPI examples, and setup documentation.

## Local development
Install Java 21 and Maven. Point DB_URL, DB_USER, and DB_PASSWORD at PostgreSQL,
then run `mvn spring-boot:run`. Defaults are localhost:5432/bank and bank/bank.
Swagger UI: /swagger-ui/index.html. OpenAPI JSON: /v3/api-docs.

The service currently provides POST /api/accounts and GET /api/accounts/{id}.
Amounts use BigDecimal and database NUMERIC, never floating point.

# Bank Transfer Service

[![Build and test](https://github.com/akshitpalamthody420/bank-transfer-service/actions/workflows/ci.yml/badge.svg)](https://github.com/akshitpalamthody420/bank-transfer-service/actions/workflows/ci.yml)

A Java backend for creating accounts, transferring money and viewing transfer history.
Transfers roll back if something fails, and an idempotency key prevents the same
payment from going through twice.

Built with Java 21, Spring Boot, PostgreSQL, JUnit, Docker and GitHub Actions.
This is a learning project using GBP balances. There is no login or real payment processing.

## Setup

Install Git and Docker with Compose. Make sure Docker is running, then:

```bash
git clone https://github.com/akshitpalamthody420/bank-transfer-service.git
cd bank-transfer-service
docker compose up --build --wait
```

This starts the API and database. Ports **8080** and **5432** need to be free.
The database tables are created automatically on startup.

Open [Swagger UI](http://localhost:8080/swagger-ui/index.html) to try the endpoints.
The API runs at `http://localhost:8080`.

To stop it:

```bash
docker compose down
```

Your data is kept between runs. Use `docker compose down -v` if you want to delete
it and start fresh.

## Try a transfer

In Swagger UI:

1. Use `POST /api/accounts` to create two accounts. The request looks like this:

   ```json
   {"ownerName": "Alice", "startingBalance": 100.00}
   ```

2. Copy their IDs and use `POST /api/transfers`:

   ```json
   {
     "fromAccountId": "SOURCE_ACCOUNT_ID",
     "toAccountId": "DESTINATION_ACCOUNT_ID",
     "amount": 25.00
   }
   ```

3. Set the `Idempotency-Key` header to something like `transfer-001`.
4. Check the balances with `GET /api/accounts/{id}` and the history with
   `GET /api/accounts/{id}/transactions`.

Use a new key for each new transfer. Repeating the same request and key returns
the original result. Changing the details with that key returns `409`.
Insufficient funds returns `422`. Amounts support up to two decimal places.

## Local development

To run the app outside Docker, install Java 21 and Maven 3.9+. From the cloned
repo, start just the database and run Spring Boot:

```bash
docker compose up -d db
mvn spring-boot:run
```

The database defaults are `localhost:5432/bank`, username `bank`, password `bank`.
You can override these with `DB_URL`, `DB_USER` and `DB_PASSWORD`.
Don't run this alongside the full Docker stack, since both apps use port 8080.

## Tests

With Java 21, Maven and Docker running:

```bash
mvn verify
```

The tests use a separate PostgreSQL container. They cover transfers, validation,
insufficient funds, duplicate requests, rollback and concurrent transfers.
GitHub Actions runs them on every push and pull request, then checks the Docker
app through its HTTP endpoints.

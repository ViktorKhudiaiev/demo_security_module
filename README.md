# Secure Transfer Demo

Minimal Java/Spring Boot multi-module demo for secure account-to-account transfers with transaction integrity validation.

This repository is packaged as one runnable demo application to make review and local execution simple. The module boundaries are intentionally aligned with a production target where each module can become an independently deployed service.

## Quick overview

For a short non-technical overview, open:

- `docs/secure-transfer-landing.html`

For visual workflow diagrams, open:

- `docs/transaction-security-visual-workflow.html`

## Modules

- `account-transfer-app` - Spring Boot application, REST API, account/ledger/transfer persistence, H2 schema.
- `tokenization-module` - HMAC-SHA256 token generation over canonical transaction payload.
- `transaction-security-module` - DB outbox trigger, scheduled outbox publisher, queue boundary, security validation worker, and security ports.

## Package structure

`account-transfer-app` is split by application layer:

```text
com.demo.securetransfer
+-- adapter/security  # adapters from app persistence to security-module ports
+-- controller        # REST endpoints
+-- domain            # Account, LedgerEntry, Transaction and status/type enums
+-- dto               # API request/response DTOs
+-- exception         # API exception mapping
+-- repository        # DB access through JdbcTemplate
+-- service           # use cases and DTO mapping
```

Repositories map database rows into domain models, not API DTOs. DTOs are created in the service layer for controller responses.

`transaction-security-module` follows the same idea:

```text
com.demo.securetransfer.security
+-- domain      # security event/view records
+-- messaging   # queue boundary
+-- port        # interfaces implemented by the app module
+-- repository  # outbox DB access
+-- service     # outbox publisher and validation worker
+-- trigger     # H2 database trigger
```

`tokenization-module` is intentionally small:

```text
com.demo.securetransfer.tokenization
+-- domain
+-- service
```

## What is implemented

- Accounts do not store a trusted `amount` field.
- Balance is calculated from immutable `ledger_entries`.
- Transfers are stored in append-only `transactions` with an HMAC-SHA256 token.
- `transactions` is protected from `UPDATE` and `DELETE` by database triggers.
- Status changes are stored in `transaction_status_events` as immutable events.
- A database trigger writes pending transactions into `transaction_outbox`.
- A scheduled security worker reads the outbox, publishes the event into an in-memory queue, recalculates the token, and appends the validation result.
- If the token does not match, both accounts are blocked and the latest transaction status event becomes `SUSPICIOUS`.

The in-memory queue is intentionally small and replaceable. It represents the ActiveMQ boundary for the demo without requiring a broker installation.

## Demo packaging vs production architecture

The demo runs as a modular monolith:

- one repository,
- one deployable Spring Boot application,
- three internal modules,
- one H2 database,
- in-memory queue boundary instead of an external broker.

In a production implementation, these boundaries should be deployed as separate services, often in separate repositories:

- `account-transfer-service` - public API, account and transfer orchestration.
- `tokenization-service` - protected token generation API backed by managed secrets.
- `transaction-security-service` - asynchronous validation worker and incident handling.

The production version should use cloud-managed infrastructure where appropriate:

- managed database,
- external broker such as ActiveMQ, RabbitMQ, or Kafka,
- independently scalable workers,
- service-level authentication and authorization,
- separate database users/roles for application writes, validation writes, and read-only access.

The outbox event is intentionally part of the design. A database trigger records the event, and a broker-backed consumer processes events at the capacity available to the security service. For moderate workloads ActiveMQ or RabbitMQ is sufficient; for high-throughput event streams Kafka is a stronger fit.

## Run

```powershell
.\scripts\run.ps1
```

The API starts on:

```text
http://localhost:8080
```

H2 console:

```text
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:file:./data/secure-transfer-demo
User: sa
Password:
```

## API examples

Create two accounts:

```powershell
$a = Invoke-RestMethod -Method Post http://localhost:8080/api/accounts `
  -ContentType 'application/json' `
  -Body '{"name":"Alice","initialBalanceCents":10000}'

$b = Invoke-RestMethod -Method Post http://localhost:8080/api/accounts `
  -ContentType 'application/json' `
  -Body '{"name":"Bob","initialBalanceCents":0}'
```

Create a transfer:

```powershell
$body = @{
  fromAccountId = $a.id
  toAccountId = $b.id
  amountCents = 2500
} | ConvertTo-Json

Invoke-RestMethod -Method Post http://localhost:8080/api/transfers `
  -ContentType 'application/json' `
  -Body $body
```

Wait one or two seconds, then check balances and the latest transaction status:

```powershell
Invoke-RestMethod http://localhost:8080/api/accounts
Invoke-RestMethod http://localhost:8080/api/transfers
```

## Important design choice

`accounts` has no balance column. The source of truth is `ledger_entries`.

`transactions` is append-only. The row represents the original transfer request and integrity token. Processing state is stored separately in `transaction_status_events`, so status changes do not mutate the original transaction payload.

`transactions.amount_cents` is allowed because it is the amount of a particular transfer, not the current account balance. Money amounts are stored in cents as `BIGINT`, not as floating-point numbers.

The token is not reversible encryption. It is a keyed HMAC signature over stable transaction fields:

- transaction id
- source account id
- target account id
- amount in cents
- created timestamp

Status is intentionally not included because it changes during processing and is stored in `transaction_status_events`.

## Database integrity model

The demo separates immutable transaction facts from processing state:

- `transactions` stores the original transfer payload and token.
- `transaction_status_events` stores status history (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `SUSPICIOUS`).
- `transaction_outbox` stores database events emitted after transaction insertion.
- `ledger_entries` stores immutable debit/credit entries used to derive account balances.

This structure makes the original transaction payload auditable and prevents normal application flow from rewriting transfer facts after creation.

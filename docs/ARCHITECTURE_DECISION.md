# Architecture: Current Demo and Target Production

## Current Demo Implementation

In this demo, the solution is implemented as one deployable application (modular monolith) with three internal modules:

1. `account-transfer-app`
2. `tokenization-module`
3. `transaction-security-module`

All three modules run in one runtime process and use one database. This packaging is a demo convenience, not the target deployment model.

The demo keeps the modules together so the full workflow can be reviewed and run locally without cloud accounts, external brokers, service discovery, or multi-repository setup.

## Persistence Model

The demo uses an append-only transaction model:

1. `transactions` stores the original transfer payload and integrity token.
2. `transaction_status_events` stores the processing status history.
3. `transaction_outbox` stores database events emitted after transaction insertion.
4. `ledger_entries` stores immutable debit and credit entries used to derive account balances.

The `transactions` table is protected from `UPDATE` and `DELETE` by database triggers. Processing state is appended to `transaction_status_events` instead of mutating the original transaction row.

## Target Production Architecture

For production, the target architecture is three separate deployable services, preferably owned and released as separate repositories:

1. `account-transfer-service`
2. `tokenization-service`
3. `transaction-security-service`

Each service is deployed independently in cloud infrastructure (for example AWS, Azure, GCP, or another managed platform) and communicates through explicit service APIs and messaging.

## Target Service Responsibilities

`account-transfer-service`:

1. exposes the account and transfer API,
2. validates business preconditions,
3. requests a token from `tokenization-service`,
4. inserts the append-only transaction payload,
5. returns the initial transfer response.

`tokenization-service`:

1. owns token generation,
2. protects signing secrets,
3. exposes a small authenticated API for token generation,
4. can be isolated from direct database access.

`transaction-security-service`:

1. consumes transaction events,
2. reads the original transaction payload,
3. recomputes the token through `tokenization-service`,
4. appends status events,
5. writes ledger entries for valid transfers,
6. blocks accounts and creates incident records for suspicious transfers.

## Messaging Model

The demo uses an in-memory queue to represent the broker boundary. In production, the outbox should feed an external message broker:

1. ActiveMQ or RabbitMQ for straightforward queued processing,
2. Kafka for high-throughput event streams and replay-oriented processing.

The broker decouples transaction creation from security validation. If the validation service is temporarily slower than the incoming transaction rate, events remain queued and are processed as capacity becomes available.

## Production-Level Additions

In the target architecture, these components are included:

1. external message broker (ActiveMQ / RabbitMQ / Kafka),
2. service-to-service security and secret management,
3. observability (tracing, metrics, structured logs),
4. independent CI/CD pipelines per service,
5. database role separation for application writes, security validation, and read-only access,
6. cloud deployment configuration for each service,
7. alerting for suspicious transactions and queue lag.

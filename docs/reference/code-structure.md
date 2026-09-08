# Code structure and reading guide

The repository contains three Java service modules and an offline documentation folder. This guide describes the current responsibility-based packages. Package organization improves navigation; it does not create new trust boundaries, change the byte-level protocol or turn the demo into a different architecture.

## Start with the request path

| Read | Source | Responsibility |
|---|---|---|
| 1 | [TransferController](../../account-transfer-app/src/main/java/com/demo/transferapp/controller/TransferController.java) and [TransferRequest](../../account-transfer-app/src/main/java/com/demo/transferapp/dto/TransferRequest.java) | HTTP request shape, validation, service calls and response/error mapping |
| 2 | [TransferService](../../account-transfer-app/src/main/java/com/demo/transferapp/service/TransferService.java) | Operation creation, issuance, idempotent Primary publication, funding and correction/reversal requests |
| 3 | [Operation](../../tokenization-module/src/main/java/com/demo/integrity/model/Operation.java) and [CanonicalEncoder](../../tokenization-module/src/main/java/com/demo/integrity/crypto/CanonicalEncoder.java) | Immutable authenticated payload and exact canonical bytes |
| 4 | [IssuanceService](../../tokenization-module/src/main/java/com/demo/keyservice/service/IssuanceService.java) and [LocalKeyVault](../../tokenization-module/src/main/java/com/demo/keyservice/vault/LocalKeyVault.java) | Independent receipt before response, issuance retries, local keys, rotation and checkpoint signing |
| 5 | [Scheduler](../../transaction-security-module/src/main/java/com/demo/securityapp/scheduling/Scheduler.java), [Processor](../../transaction-security-module/src/main/java/com/demo/securityapp/service/Processor.java) and [OperationDelivery](../../transaction-security-module/src/main/java/com/demo/securityapp/delivery/OperationDelivery.java) | Periodic dispatch/reconciliation, persistent delivery, verification and protected settlement |
| 6 | [AuditLog](../../transaction-security-module/src/main/java/com/demo/securityapp/audit/AuditLog.java) and [MerkleTree](../../transaction-security-module/src/main/java/com/demo/securityapp/crypto/MerkleTree.java) | Ordered history, checkpoint handling and inclusion proofs |
| Incident side path | [NotificationOutbox](../../transaction-security-module/src/main/java/com/demo/securityapp/notification/NotificationOutbox.java), [NotificationDispatcher](../../transaction-security-module/src/main/java/com/demo/securityapp/notification/NotificationDispatcher.java) and [SmtpNotificationSender](../../transaction-security-module/src/main/java/com/demo/securityapp/notification/SmtpNotificationSender.java) | Durable first alert per operation, fenced retry claims and minimal plain-text SMTP delivery outside financial execution |

Follow `Processor`'s discovery/reconciliation, processing, settlement and outcome-relay methods alongside [the numbered architecture flow](architecture.md#delivery-and-execution-order). VERIFIED is an audit event, not permission to execute an arbitrary later version of the source row. Processing binds the financial effect to the checked immutable snapshot and a unique operation identity.

## Package responsibilities

### Transfer application: `com.demo.transferapp`

| Package | Contents |
|---|---|
| Root | `TransferApplication`: Spring Boot entry point |
| `controller` | `TransferController`: HTTP boundaries |
| `dto` | `AccountRequest`, `TransferRequest`, `FundingRequest`: inbound transport records |
| `service` | `TransferService`: application use cases and Primary publication |
| `client` | `ProcessorClient`: calls to the protected processor |
| `config` | `AppConfiguration`: dependency/configuration wiring |
| `security` | `ApiAuthorization`: demo API access checks |

The application does not calculate authoritative balances from Main rows. Registration/funding and reads of protected account state use the processor API. It requests HMAC issuance but does not receive the secret key.

### Shared integrity protocol: `com.demo.integrity`

| Package | Contents |
|---|---|
| `model` | `Operation`, `Checkpoint`: immutable business/protocol values |
| `dto` | `SignedOperation`, `SignedCheckpoint`, `VerificationResponse`, `IssuanceInventory`, `IssuanceInventoryItem`, `PublicKeyInfo`: typed envelopes and transport results |
| `crypto` | `CanonicalEncoder`, `CheckpointEncoder`: validation/encoding and related cryptographic helpers |
| `client` | `IntegrityClient`: typed key-service HTTP adapter |

The shared protocol currently lives in `tokenization-module` alongside the key service. Maven also produces that module's executable key-service JAR; its library classes are used by the other two modules. It is not a fourth running service. Splitting it into a separate Maven protocol artifact would be an optional later packaging change, not a prerequisite for this demo.

### Key service: `com.demo.keyservice`

| Package | Contents |
|---|---|
| Root | `KeyServiceApplication`: Spring Boot entry point |
| `controller` | `KeyController`: issue/verify, inventory, rotation and checkpoint endpoints |
| `service` | `IssuanceService`: independent receipt lifecycle and retry rules |
| `vault` | `LocalKeyVault`: local software key storage, HMACs and Ed25519 checkpoint signatures |
| `config` | `StrictJsonConfiguration`: strict JSON handling |
| `security` | `ServiceAuthenticationFilter`: separated service permissions |
| `exception` | `ConflictException`: explicit issuance conflict |

This vault is the demo implementation. The production architecture calls for independent KMS/HSM custody and governance; moving a class into `vault` does not implement those controls.

### Integrity processor: `com.demo.securityapp`

| Package | Contents |
|---|---|
| Root | `SecurityApplication`: Spring Boot entry point |
| `controller` | `InternalController`: internal accounts, operations, audit and authorized test endpoints |
| `dto` | `RegisterAccountRequest`, `AccountHoldRequest`, `FaultRequest`, `MerkleProofStep` |
| `domain` | `Account`: protected account state |
| `service` | `Processor`: job dispatch, reconciliation, verification, accounting and outcome relay |
| `audit` | `AuditLog`: ordered events, audit health, checkpoints and proof assembly |
| `crypto` | `MerkleTree`: hashing and inclusion-proof operations |
| `delivery` | `OperationDelivery`, `EmbeddedActiveMqDelivery`: delivery contract and persistent local broker adapter |
| `notification` | `IncidentNotification`, `NotificationStatus`, `NotificationOutbox`, `NotificationDispatcher`, `NotificationSender`, `SmtpNotificationSender`, `NotificationSettings`, `NotificationConfiguration`, `NotificationScheduling`: incident messages, durable claims, SMTP, validated settings and independent scheduling |
| `client` | `KeyGateway`: key-service adapter |
| `config` | `DatabaseConfig`, `DeliveryConfiguration`: SQL pools/transactions and delivery wiring |
| `security` | `ServiceAuthorization`: internal API role checks |
| `scheduling` | `Scheduler`: periodic processing triggers |

The `delivery` boundary is explicit so broker-specific work is separate from verification/accounting. A different broker still needs a reviewed adapter with matching acknowledgement, durability and retry semantics; Kafka is not an implemented configuration-only substitution.

The `notification` package is a separate side channel, not part of the ActiveMQ financial-delivery adapter. `AuditLog` atomically inserts the initial notification with an integrity incident, and `NotificationDispatcher` sends only after its protected claim transaction commits. SMTP availability is not an execution gate. The immutable `IncidentNotification` record describes a persisted delivery item; `NotificationStatus` is an operator-only status DTO. Both are intentionally located with their cohesive notification subsystem rather than duplicated in another package. See [notification configuration and guarantees](notifications.md).

## DTO, protocol model or database entity?

A DTO (data transfer object) describes a message crossing a boundary. A domain/protocol model describes the values and meaning the application operates on. Neither needs mutable fields, a large number of methods or a Java class rather than a record. JSON annotations alone do not decide the category.

| Example | Classification | Why |
|---|---|---|
| `TransferRequest` | Request DTO | Describes the external transfer API, including validation and transport field names |
| `Operation` | Immutable business/protocol model | Describes the versioned operation committed by canonical bytes: identity, accounts, amount, time, linkage and retry identity |
| `Checkpoint` | Immutable protocol model | Describes a log/tree-size/root/time commitment before signature wrapping |
| `SignedOperation` | Transport envelope | Carries an `Operation`, `keyId`, content hash and HMAC; its existing name does **not** imply an asymmetric signature |
| `SignedCheckpoint` | Transport envelope | Carries a checkpoint with its asymmetric-signature metadata |
| `Account` | Protected domain state | Represents an account read from authoritative accounting state; it is not a JPA entity |
| `MerkleProofStep` | Proof DTO | Transfers one structured part of an inclusion proof |

`Operation` is intentionally reused as the key-service JSON payload and the immutable canonical business value; the demo does not maintain a second identical class just to add another layer. Database serialization of this value is separate from the canonical byte encoding used for authentication. Request records have been extracted from controllers so they can be found, read and reused without opening a controller or service implementation.

Some small status/error/health responses and evidence views remain `Map` values. The source layout is not a claim that every API response has a dedicated DTO. Likewise, `Processor` remains a compact orchestrator with explicit JDBC operations; extracting a repository/interface for every table or splitting each method into a service is not part of this organization change.

## Where the database structure lives

The project uses Spring `JdbcTemplate` and `TransactionTemplate`, not JPA/Hibernate. There are no hidden ORM entities or entity-generated tables. SQL schemas define persisted rows, constraints and runtime privileges:

| Schema source | Physical database | Contents |
|---|---|---|
| [primary-schema.sql](../../account-transfer-app/src/main/resources/primary-schema.sql) | Main/Primary | Source operations, accounts metadata, outbox hints and status projections |
| [key-schema.sql](../../tokenization-module/src/main/resources/key-schema.sql) | Audit/Protected | Independent issuance receipts |
| [audit-schema.sql](../../transaction-security-module/src/main/resources/audit-schema.sql) | Audit/Protected | Ordered audit history and checkpoints |
| [settlement-schema.sql](../../transaction-security-module/src/main/resources/settlement-schema.sql) | Audit/Protected | Authoritative accounts, jobs, journal/postings, results and durable outcome work |
| [notification-schema.sql](../../transaction-security-module/src/main/resources/notification-schema.sql) | Audit/Protected | Additive incident-delivery outbox, unique operation identity, attempts, expiring claim and SMTP-accepted marker |

`settlementDb` is a logical connection pool using a separate restricted role in the same protected database. It does not mean a third PostgreSQL instance. The separate roles are retained even though accounting and audit share one physical protected store; that store's administrator remains trusted. Java performs calculations while SQL transactions and constraints enforce atomicity and uniqueness.

SQL remains next to the use case that owns the transaction boundary. Keeping debit, credit, result identity and outcome creation in one protected transaction is more important than creating a generic repository abstraction for appearance.

## Tests and maintenance

Java tests live under each module's `src/test/java`; package-local tests follow their production responsibility where appropriate, while cross-layer integration tests may remain at a service root. Resource schemas stay under `src/main/resources`. Startup/fault/load scripts remain under `scripts`; the optional browser lab and documentation maintenance tools live under `docs/live` and `docs/build`.

Use [the runbook](running.md) for build, test, startup and recovery commands. Read [the latest failed throughput evidence](../evidence/local-verification-2026-09-07.json), [historical successful run](../evidence/local-verification-2026-09-06-final.json) and [notification functionality](../evidence/notification-verification-2026-09-08.json) separately. A result describes its original run, not automatic certification of a later refactor. Source moves must preserve wire field names, canonical bytes, SQL schemas and runtime authorization, and must be followed by compilation and regression tests.

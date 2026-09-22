# Record Integrity

A runnable Java/PostgreSQL demo of authenticated operations, independent evidence and protected execution. It demonstrates how a forged or changed row in a compromised source database can be refused before it changes protected money state. All accounts and funds are simulated; no real payments are made.

This is an engineering integration of established cryptographic and database techniques, not a new cryptographic primitive or a production banking system. It assumes the attacker controls Main/Primary PostgreSQL but not the trusted application, key service, processor, protected database or host.

## Read the demo without running anything

**Open [docs/index.html](docs/index.html) in a web browser.** After cloning or downloading the repository, double-click that file or use the browser's Open File command. The overview, architecture, sequence, terms and recorded evidence work offline. Java, Docker, Node.js and a server are not required to read them.

GitHub displays HTML source rather than executing the page. Download the repository or the [documentation ZIP](docs/downloads/demo-materials.zip), extract it, and open `docs/index.html` locally. Keep the complete `docs` folder together for companion links. The diagrams are explanations, not live database views. External reference links need Internet only when opened.

- [Article](docs/article/index.html) · [PDF](docs/article/article.pdf)
- [Publishing guide](docs/article/publishing-guide.md) · [LinkedIn publishing kit](docs/article/linkedin-post.html) · [Medium article](docs/article/medium-article.html)
- [PowerPoint](docs/presentation/demo.pptx) · [Presenter guide](docs/presentation/guide.html) · [Speaker notes](docs/presentation/notes.md)
- [Code structure and reading guide](docs/reference/code-structure.md)
- [Architecture and acceptance criteria](docs/reference/architecture.md) · [Exact protocol](docs/reference/protocol.md) · [Glossary](docs/reference/glossary.md)
- [Integrity incident email notifications](docs/reference/notifications.md)

## What runs in the local demo

| Component | Purpose | Local endpoint |
|---|---|---|
| Transfer application | Accept requests, obtain authenticated operations, commit source records and outbox hints | `127.0.0.1:8080` |
| Key service | Issue/verify HMACs, retain independent issuance receipts, rotate keys and sign checkpoints | `127.0.0.1:8081` |
| Integrity processor | Deliver jobs, verify source content, execute protected accounting and retain audit outcomes | `127.0.0.1:8082` |
| Main/Primary PostgreSQL | Candidate operations, delivery hints and non-authoritative status projections | `127.0.0.1:55431` |
| Audit/Protected PostgreSQL | Issuance receipts, audit history, jobs, authoritative balances and postings | `127.0.0.1:55432` |
| Embedded ActiveMQ Classic | Persistent delivery buffer inside the processor JVM; VM transport only | No broker TCP endpoint |
| Local Mailpit inbox | Capture incident-notification emails for inspection; no delivery to public mailboxes | SMTP `127.0.0.1:1025`, inbox `127.0.0.1:8025` |
| Optional Live Lab helper | Loopback-only browser experiments using the real local stack | `127.0.0.1:8090` |

There are **two physical databases**, not three. The logical accounting connection is still named `settlementDb` and uses its own SQL role in Audit/Protected. Java performs the business calculations; PostgreSQL provides atomic commits, locks, uniqueness and durable storage. Audit/Protected is not merely a passive log: its authoritative money state must stay outside the Main DBA's control for the stated guarantee to hold.

## How a transfer is processed

1. The trusted application validates the request and asks the key service to authenticate the immutable `Operation`. Business-user authorization is an upstream responsibility; the demo uses separated service/API credentials, not a full identity provider.
2. The key service encodes the versioned payload deterministically, computes HMAC-SHA256 and commits an independent issuance receipt before returning the authenticated envelope. The application never receives the secret key.
3. One Primary SQL transaction writes `transactions` and `transaction_outbox`. There is no PostgreSQL trigger, notification channel or database call into the security service.
4. The processor polls outbox hints, publishes persistent UUID messages to embedded ActiveMQ, and acknowledges a source hint only after broker acceptance. Its transacted consumer commits a unique protected SQL job before acknowledging the message.
5. A worker resolves the independent receipt, compares the source record and verifies its HMAC through the key service. It records verification, then re-reads the source and requires the same checked snapshot before execution.
6. One protected SQL transaction commits Java-calculated debit/credit postings, balances, the unique execution result and a durable outcome. The relay subsequently appends audit history and projects status into Primary. Duplicate hints or retries do not cause a second financial effect.

Periodic reconciliation also checks for missing issued records and fabricated source rows without delivery hints. Detection is not instantaneous. Audit events are committed to a Merkle tree with signed checkpoints; those checkpoints need independently trusted retention for protection against coordinated rollback of the trusted stores. See [architecture](docs/reference/architecture.md) for acknowledgement gaps, failure behavior and the production boundary.

On an integrity incident, the processor atomically appends protected audit evidence and queues the first email notification for that operation. A separate dispatcher sends a minimal alert with retry and a rate cap; mail-server availability is not an execution gate. Normal local startup captures messages in [Mailpit's local inbox](http://127.0.0.1:8025), not in Gmail or another public mailbox. Real email needs separately configured authenticated SMTP. See [notification setup and limits](docs/reference/notifications.md), including possible duplicate delivery and the distinction between a suspected discrepancy and confirmed fraud. Existing measurements below predate this feature; no new throughput result is implied.

## Source layout

Each service has responsibility-based packages rather than all classes in one package. Application entry points stay at their service package roots so Spring can discover the child packages.

```text
account-transfer-app/src/main/java/com/demo/transferapp/
  TransferApplication.java
  controller/   HTTP endpoints and response/error mapping
  dto/          AccountRequest, TransferRequest, FundingRequest
  service/      Transfer orchestration and source publication
  client/       Protected processor HTTP client
  config/       Application wiring
  security/     API authorization

tokenization-module/src/main/java/com/demo/
  integrity/
    model/      Operation, Checkpoint: immutable protocol payloads
    dto/        Authenticated envelopes, verification and inventory messages
    crypto/     CanonicalEncoder, CheckpointEncoder
    client/     IntegrityClient
  keyservice/
    KeyServiceApplication.java
    controller/ service/ config/ security/ vault/ exception/

transaction-security-module/src/main/java/com/demo/securityapp/
  SecurityApplication.java
  controller/   Internal HTTP endpoints
  dto/          Request messages and Merkle proof steps
  domain/       Protected Account model
  service/      Verification, accounting, job handling and reconciliation
  audit/        Ordered audit history, checkpoints and proof assembly
  crypto/       Merkle tree operations
  delivery/     Delivery interface and embedded ActiveMQ adapter
  notification/ Durable incident outbox, SMTP adapter, retries and isolated scheduler
  client/       Key-service gateway
  config/       Database and delivery wiring
  security/     Service authorization
  scheduling/   Periodic processing tasks

docs/           Offline walkthrough, article, presentation and reference material
  evidence/     Sanitized dated reports, retained unchanged
  live/         Optional browser lab and its local helper/tests
  build/        Documentation generators, templates and publication checks
scripts/        Startup, stop, migration, scenarios and load/recovery verification
.local/         Ignored local runtimes, credentials, keys, backups and work files
```

Transport DTOs are named top-level records in `dto` packages; domain/protocol records are not classified solely by whether they have methods or JSON annotations. This project uses Spring JDBC and explicit SQL schemas, not JPA: an immutable `Operation` is not an ORM entity. The [code structure guide](docs/reference/code-structure.md) explains these distinctions, source entry points, persistence and the remaining deliberately compact implementation.

## Run the local services and browser lab

Requirements: Windows PowerShell, Java 21, Maven, Docker Desktop with Linux containers, and Node.js 20+ for the lab/scenario/load tools. Existing bundled Java/Maven under ignored `.local/tools` are detected; a fresh clone may use installed tools. Node discovery and optional overrides are documented in the [runbook](docs/reference/running.md).

From PowerShell in the repository:

```powershell
# Build/test the Java modules, then start the two databases and three services.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1

# Start the optional browser helper after the services are ready.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\live-demo.ps1
```

Keep the helper terminal open and visit [the local Live Lab](http://127.0.0.1:8090/docs/live/index.html). It can prepare dedicated accounts, fund them through the protected path, submit a valid transfer, insert a fabricated Primary row and show the resulting database evidence. Repeated actions retry the same operation identity. The HTML file opened from disk cannot execute these experiments.

For an already-built checkout, `live-demo.ps1 -StartStack` starts/reuses the stack and opens the same lab workflow. It reuses existing JVM settings, so do not use that shortcut to clear fault hooks from an interrupted test. Do not expose these demo APIs or the lab helper publicly. Do not run fault/recovery/acceptance tests during a presentation.

```powershell
# Stop only this project's tracked services and Compose containers; keep data.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop.ps1
```

Startup and shutdown do not delete Docker volumes. Credentials, keys, broker journals, database contents and local reports remain private. Never distribute `.local`, `.m2-cache`, IDE state or Maven `target` directories.

## Test and verify

```powershell
# Java unit/component tests and executable JAR packaging.
# Stop this project's JVMs first to release running JARs on Windows.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop.ps1 -KeepDatabases
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test.ps1

# Full acceptance: build/tests, startup, adversarial PostgreSQL scenarios,
# 20 offered transfers/second for 120 seconds, and normal-mode restoration.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-local.ps1 -DurationSeconds 120 -RateTolerance 0

# Real local service outages/recovery: run separately, never during the load test.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\recovery.ps1
```

The full suite checks protected funding, duplicate/conflicting retries, role boundaries, forgery, missing records, tampering/deletion after verification, rollback and delivery replay. Load success requires unique completed operations, correct protected balances/postings and durable audit outcomes, not simply successful HTTP submissions. New reports are written under ignored `.local`; inspect `passed` and restoration status. See the [runbook](docs/reference/running.md) for individual Node suites, fault hooks, ownership safeguards and migration.

The package-layout revision passed 92 Java tests, 40 Node checks, 7 publication-validator tests and all 15 PostgreSQL scenarios. In run `2026-09-07T01-27-24-67e1f9c6`, all 2,400 scheduled load operations completed uniquely with correct protected accounting and audit, with zero transaction failures. However, steady completed throughput was **19.9636 TPS**, below the strict 20 TPS threshold with zero tolerance. The load gate and combined report therefore remain **FAILED**, not a fresh full acceptance pass. Normal-mode restoration passed. The retained raw report is under `.local/verification-2026-09-07T01-27-24-67e1f9c6/report.json`; private runtime evidence is not included in the documentation ZIP.

The [sanitized September 7 report](docs/evidence/local-verification-2026-09-07.json) now makes that failed gate available without private runtime files. The September 8 incident-email implementation passed 137 Java tests in 19 suites, 44 Node checks and 18 PostgreSQL role-isolation checks before the publication update. Its [PostgreSQL-to-Mailpit run](docs/evidence/notification-verification-2026-09-08.json) passed valid/no-alert, forged/quarantined, repeated-identity and post-settlement-tampering cases. It did not run a new load benchmark, deliver to Gmail or test a production SMTP provider. These functional results do not supersede the latest throughput failure.

The subsequent documentation/publication update passed **49 Node checks**, including regressions that publish failed reports honestly and keep latest throughput, historical passes and local notification results distinct. This is a separate post-publication check count, not a relabeling of the earlier 44-check implementation run or a new financial load test.

The [September 6 final recorded run](docs/evidence/local-verification-2026-09-06-final.json) passed 86 Java tests, 15 PostgreSQL scenarios and 2,400 unique completed transfers at 20 offered TPS for 120 seconds, with zero failures and zero configured throughput tolerance. Steady completed throughput was 20.0000 TPS; whole-run throughput was 19.9173 TPS; p95 was 3,057 ms. Missing unpublished receipt detection took 32.96 seconds within the declared 120-second observation budget. These are preserved measurements of that dated run, not a new measurement of every later source change or a production SLA.

Before sharing a rebuilt documentation package, run `docs/build/validate-content.py`; [the documentation build guide](docs/build/README.md) covers generation and the explicit publication allow-list.

## Security boundary and production work

HMAC detects changes to authenticated content; independent receipts, protected execution state and reconciliation cover additional omission/replay cases. This does not prevent a database administrator from modifying or deleting Main data, restore deleted data, detect a deceived but authorized customer's intent, or protect against compromise of the trusted host/services.

The demo's key vault is a local software implementation, not a deployed KMS/HSM. Its embedded broker is persistent but shares the processor JVM/host failure domain. Local API credentials are not production IAM or encrypted service transport. Independent key custody, administration/deployment controls, externally retained checkpoints, backup/PITR, high availability and broader failure/security testing remain [production requirements](docs/reference/architecture.md#production-boundary). HMAC alone does not establish third-party non-repudiation or regulatory compliance.

## Contributions

Read or clone this public repository to study the implementation. Propose changes through a fork and pull request; public visibility does not grant write access. Changes to `master` require an explicit owner merge through a pull request. Direct pushes, force pushes and deletion are blocked by repository rules. See the [contribution and review policy](docs/reference/contributing.md) for the owner-review exception and administrative limits.

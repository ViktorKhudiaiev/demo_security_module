# Secure Transaction Integrity — Presentation Guide

> September 6 topology update: [Main + Audit/Protected with embedded ActiveMQ](../reference/architecture.md) supersedes physical three-database and direct outbox-to-inbox descriptions below. Requirement IDs and historical evidence remain preserved. September 5 metrics describe the earlier topology only.

Status: local verification PASS, 2026-09-05. The complete recorded run passed standard Maven verification, 15 PostgreSQL scenario checks, a 120-second load test with zero throughput tolerance, and final restart without test hooks. See [implementation status](../reference/implementation-status.md) for the report and remaining gates. Local test evidence is not production certification.

The authoritative current and target architecture diagrams, target sequence, and implementation coverage table are in [ARCHITECTURE_DECISION.md](../reference/architecture-decisions.md). Use them in talks to preserve status labels. The [visual workflow](workflow-2026-09-05.html) is a presentation companion.

## 1. Opening statement

> We are building an integrity layer for existing relational transaction systems. Its purpose is to prevent primary-database tampering from becoming an executed financial operation. It combines authenticated transaction content, independent verification evidence, and atomic, idempotent settlement outside the primary database administrator's control.

The engineering contribution is a reproducible integration of established mechanisms. Do not claim a new cryptographic primitive, universal administrator resistance, or regulatory compliance established by this prototype.

The work supports conferences, technical scrutiny, public GitHub review, potential integrations, and the broader EB-2 NIW evidence package. Revenue is not required to demonstrate the technical contribution.

## 2. Explain the threat first

Example: Alice creates a transfer to Bob for 25 units. An attacker changes its amount or destination directly in the primary database. The target processor must reject the changed content before money postings.

The attacker may administer that primary database and disable its triggers. The target assumes the application, keys, verifier, processor, independent evidence, settlement database, and their credentials remain outside the attacker's control. The local host administrator is not the attacker modeled by this demonstration.

The integrating application handles user identity and permission to spend. The integrity module handles service access to generation and verification: database credentials must not grant access to either the key or the MAC generation operation. No new end-user IAM subsystem or mandatory actor field is needed for this scope.

## 3. Present both architecture views

Show **Current local implementation** from the architecture document first. It has three JVM services, separate PostgreSQL instances/admin credentials, restricted software-key APIs, durable issuance/inbox/outcome evidence and atomic protected settlement. The legacy H2 trigger and shared YAML key are removed. Cloud KMS, independent hosts/IAM and WORM are not present. Present the measured scenario/load results with their exact coverage and the complete passing verification report.

Show **Full target** next. Explain the primary data, key access, independent evidence, and money execution boundaries. The diagram includes both controls planned for the local implementation and controls requiring independently managed production infrastructure.

| Label | What may be claimed |
|---|---|
| Current code | Component/code path exists; limitations are disclosed |
| Locally exercised implementation | Standard build/tests and recorded PostgreSQL scenarios/load; full-run status and limitations disclosed |
| Production infrastructure | Independent infrastructure or operating procedures are required beyond one host |
| Measured result | A recorded experiment identifies environment, workload, and observed outcome |

A planned arrow is not a demonstrated security property. Multiple repositories, an external broker, and cloud accounts are not prerequisites for the planned local demo.

## 4. Narrate the intended transfer

1. The existing application checks its user's permissions and submits immutable facts to the integrity API with its service identity.
2. The integrity service computes a MAC and commits an independent issuance receipt before returning it.
3. The application persists the transfer and source outbox in one local database transaction.
4. The verifier treats primary contents/events as untrusted, checks the MAC and independent issuance binding, and stores the exact verified snapshot and hash.
5. The processor compares current primary facts with that evidence. Missing or changed content is quarantined. After a match, it executes the exact checked bytes without rereading mutable payment fields.
6. The protected settlement database locks affected accounts and checks authoritative funds and account controls. Operation identity, journal, paired postings, result, and outcome outbox commit atomically. An identical retry returns the existing result.
7. A durable relay projects the committed result into audit and display status. These projections can lag; they do not authorize payments.
8. Signed Merkle checkpoints support external history verification. Completeness and rollback detection require independently retained, sufficiently recent checkpoints.

The architecture document contains the sequence and crash-handling rules. There is no claimed ACID commit spanning all databases. A missing primary row does not by itself prove a legitimate rollback; independent receipts remain available for investigation.

## 5. Demonstration script — recorded local evidence and rehearsal checks

Run `scripts/verify-local.ps1` before rehearsal. It executes the automated scenario/load harness and retains reports. A separate [recorded recovery run](../evidence/local-recovery-2026-09-05.json) passed fixture setup and two real JVM stop/restart scenarios: processor outage with durable queued work and key-service outage refusing new issuance. Component exceptions and these bounded JVM outages are not host-power-loss, database-outage or PITR recovery tests. Read [current limitations](../reference/implementation-status.md) before using the production target diagram.

The recorded 15-check PostgreSQL harness covers service authorization, protected funding, normal transfer, conflicting/concurrent retries, primary-role denial, fabrication, missing issuance, post-verification tampering/deletion, injected settlement rollback, replay and post-settlement tampering. In the separate processor-outage test, a 503 occurred after durable publication: no money moved during the outage, restart drained the queued operation without client republication, and identical-key retry did not duplicate it. In the key-service-outage test, new issuance returned 503 without receipt, source row or balance change; restart and retry settled once. Both checked exact payload, paired postings, balances and audit; final normal restart passed. Not every demonstration below has live evidence: in-flight key-service failure, database outages, key-rotation/history demonstrations, host power loss and restore drills retain separate evidence gates.

| Scenario | Expected observable result |
|---|---|
| Protected funding and normal transfer | Authenticated funding evidence, one balanced settlement per operation, correct balances |
| Change amount or recipient with primary DBA access | Incident and operation quarantine; no unauthorized postings |
| Fabricate a transaction | No valid independently bound MAC; no settlement |
| Repeat a valid operation/event | Existing result returned; no second debit/credit |
| Change primary facts after verification | Final current-content check rejects the mismatch |
| Change primary facts after the final comparison | Posted fields remain the checked immutable snapshot; later discrepancy is detectable |
| Delete intent before first verifier poll | Issuance evidence remains; reconciliation identifies missing/unresolved intent after the defined window |
| Crash around settlement commit | No settlement or one complete settlement; retry does not duplicate financial effects |
| Stop required verification/evidence dependencies | Work remains durable; no new unverified operation executes |
| Rotate keys and verify history | Exact key IDs select the historical verification key |
| Modify/truncate history or restore an older snapshot | Proof, consistency, or freshness checks fail against an independent reference |
| Sustain 20 completed transfers/second | Durable terminal evidence, bounded backlog, published latency and invariant results |

Dependency unavailability must be shown separately from confirmed invalid content. If a source insert legitimately fails, trusted evidence and reconciliation determine its outcome; an absent row is not silently relabeled as harmless.

## 6. Questions specialists are likely to ask

**Does HMAC identify the attacker or establish user permission?** No. It authenticates content under a trusted key. The application owns user authorization; the integrity module restricts service access to generation and verification.

**Can an administrator bypass triggers?** Yes. Primary triggers are operational controls. The target depends on key permissions, independent evidence, and a settlement boundary that primary DBA access cannot modify.

**Why is an independent verified record insufficient?** Primary facts can change after verification. The processor compares current content and executes the exact checked snapshot. A UUID or `verified=true` flag alone does not authorize payment.

**Can audit and ledger disagree after a crash?** Temporarily, yes. Settlement and its outcome outbox commit atomically; a durable relay repairs eventual projections without repeating money movement.

**Does a HMAC mismatch freeze the recipient?** The local implementation quarantines the operation and supports separately authorized account holds; it does not automatically block the recipient. A forged record may name an innocent recipient, making automatic blocking a denial-of-service mechanism. Any automatic account-blocking policy requires a separate decision and supporting evidence.

**Does a row MAC detect deletion?** No. Independent issuance evidence, reconciliation, ordered history proofs, and externally anchored checkpoints address missing records and rollback. Auto-increment gaps are not cryptographic proof.

**Do signed Merkle checkpoints prove everything?** No. Verification needs trusted public keys, inclusion/consistency proofs, independent checkpoint retention, and a freshness expectation. A local file under the same host administrator is not an independent witness.

**Is the local key service a real KMS/HSM?** No. It models process and credential separation using software keys. Real KMS/HSM, independent IAM, and operating controls must exist before that deployment protection is claimed.

**What is the capacity claim?** The initial target is 20 fully completed transfers per second sustained, including durable terminal evidence. The accepted local 120-second run offered 20 TPS and completed all 2,400 distinct operations without failure. Measured steady-state completion was 20.009 TPS after a 10-second warmup, with zero configured throughput tolerance; including warmup and final drain, it was 19.918 TPS. Report p50/p95/p99 latency as 490/709/853 ms and final drain as 493 ms. Those results are from Windows, an Intel i7-13620H host, about 16 GB RAM, three local JVMs and three Docker PostgreSQL instances; they are not production sizing or a 10-minute soak test. The combined build/scenario/load/normal-restart report passed. See [implementation status](../reference/implementation-status.md) for raw evidence and measurement definitions.

An earlier run passed its load stage but failed the final normal-mode restart because of a PowerShell 5.1 JSON compatibility bug. The failure remains recorded; the corrected complete rerun passed. Do not present the earlier failed combined report as a success.

**Is a broker mandatory?** No. Durable polling and leases can satisfy the local workload. A broker changes transport options, not message trustworthiness.

**Is this SEC/FDA compliant or legal non-repudiation?** Neither follows from cryptography alone. The deployed system, operating controls, and use case matter; the glossary contains sourced explanations.

## 7. Before publishing results

- Pair every property with a test, recorded attack outcome, measured result, or explicit planned label.
- Include current and target views; retain local simulation and production boundary labels when exporting diagrams.
- Keep the threat model and trusted-application precondition visible.
- Publish workload, environment, duration, latency, backlog, errors, and invariant checks with benchmark numbers.
- Cover recovery, corrections, protected funding, and known limitations alongside the normal transfer.
- Use “HMAC authentication tag”; do not call it encryption or an asymmetric signature. Existing folder names are historical.

Implementation tracking: [PROJECT_MASTER_PLAN_EN.md](../reference/master-plan.md). Study material: [TECHNICAL_GLOSSARY_EN.md](../reference/glossary.md).

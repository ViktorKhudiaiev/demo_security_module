# Two-database architecture with embedded ActiveMQ

Decision date: September 6, 2026. This supersedes the physical three-database layout, not the authenticated-execution guarantees or historical measurements. All funds remain simulated.

## Agreed scope

The user selected **Main + Audit/Protected with guarantees preserved**. Main/Primary is the attacker-controlled database. Audit/Protected stores independent issuance evidence, ordered history and authoritative execution state. Java performs business checks and calculations. The database supplies atomic transactions, row locks, uniqueness and durable storage; there are no stored procedures calculating transfers.

| Component | Location | Responsibility |
|---|---|---|
| Transfer application | JVM, port 8080 | Existing business authorization, issuance request, Primary record/outbox commit |
| Key service | JVM, port 8081 | HMAC issue/verify separation, original receipt before response, rotation and checkpoint signing |
| Integrity processor | JVM, port 8082 | Dispatcher, consumer, verification, Java accounting, audit relay and reconciliation |
| Embedded ActiveMQ Classic 6.3.2 | Inside processor JVM, VM transport only | Persistent UUID hints in local KahaDB; no TCP listener or separate broker server |
| Main/Primary PostgreSQL | Port 55431, primary_db | Candidate operations, outbox hints, status projections |
| Audit/Protected PostgreSQL | Port 55432, audit_db | Receipts/history plus jobs, protected balances, postings and execution outcomes |

Audit/Protected is not a passive log-only database. If authoritative balances and execution markers belonged to a fully compromised Main DBA, the attacker could change money state without presenting an HMAC. This is why these small but critical tables remain protected.

## Database roles and tables

| Runtime role | Allowed table group | Deliberately excluded |
|---|---|---|
| primary_app | Insert source records and delivery hints | Protected SQL and secret keys |
| primary_processor | Read source/hints, acknowledge hints, insert status projections | Alter operation content |
| audit_key | Read/insert issuance_receipts, use its sequence | Accounting and audit-history writes |
| audit_processor | Append audit_events/checkpoints, advance audit_head | Accounting and issuance writes |
| settlement_processor | Jobs, accounts, journal, postings, results, outcome outboxes | Issuance/audit-history writes |

`settlementDb`, `processor.settlement` and `settlement_outbox` are logical accounting names retained to keep the change small. They no longer identify a third PostgreSQL database. Both protected connection pools point to audit_db with different credentials. The common Audit DBA and local host administrator remain trusted.

## Delivery and execution order

1. The trusted application requests an HMAC over exact canonical bytes.
2. The key service commits the independent original receipt before returning the authenticated operation.
3. One Primary SQL transaction commits the operation and its outbox hint. No trigger or notification channel initiates processing.
4. The dispatcher polls IDs and performs a synchronous persistent ActiveMQ send. It acknowledges Primary only after broker acceptance.
5. A transacted JMS consumer inserts the unique protected SQL job. It commits the receive only after SQL commits. Failure rolls back delivery; an unrecoverable session disables readiness.
6. The worker claims the job, resolves the independent receipt FIRST, reads and compares source content, and verifies the HMAC through the key service.
7. It records VERIFIED, re-reads Primary, and requires the same checked snapshot.
8. Java calculates from that snapshot. One protected SQL transaction commits paired postings, balances, journal, unique result, durable outcome and job completion.
9. The durable relay appends the audit outcome and projects status into Primary. It never executes another debit.

Each acknowledgment gap permits duplicate delivery. Unique operation identity and the atomic protected transaction prevent duplicate financial effects. There is no XA/global transaction across PostgreSQL and JMS. VERIFIED alone is never approval to settle.

Two bounded Java workers process admitted jobs concurrently. Failed/interrupted batches retain admission until all submitted futures finish. Forced shutdown cancels removed queued futures so batch waiters can terminate. A batch admits at most 100 jobs. SQL job claims and stable account-lock ordering remain the correctness controls; thread count does not grant additional execution authority.

Independent reconciliation checks missing issued records and fabricated source rows, even without outbox hints. It processes up to five 100-receipt pages per scheduled invocation plus a 100-row Primary scan. This is a bounded periodic scan, not instantaneous deletion detection. The unpublished-receipt scenario now permits a declared 120-second observation budget for retained history, rather than the former 30 seconds. This is a test timeout, not a guaranteed detection bound. A subsequent 30-second check did time out; read-only follow-up confirmed the operation was quarantined with no postings and a relayed outcome. Production requires measured reconciliation-lag monitoring and a scalable incremental discovery design. A five-second publication grace distinguishes an unfinished publication window from a reportable discrepancy; it does not prove malicious intent.

## What embedded messaging does and does not add

Implemented: persistent messages without expiration, KahaDB disk-sync `always`, separate producer/consumer sessions and locks, 32 MiB broker memory / 512 MiB store / 64 MiB temporary-store limits, bounded producer failure on pressure, delayed unlimited redelivery for valid hints after SQL failure, and a durable invalid-envelope queue. Only canonical UUID text plus protocol version is accepted. Object messages are never deserialized.

The broker provides a local persistent buffer. It shares the processor JVM and host failure domain. It does not establish high availability, higher throughput, horizontal scaling, immunity to disk loss, or production readiness. Disk-pressure behavior and broad failover remain additional validation work. Production can choose an independently deployed broker. Kafka requires a distinct adapter and reviewed offset/partition semantics; it is not an implemented drop-in switch.

## Acceptance checklist

| ID | Acceptance criterion | Evidence / status |
|---|---|---|
| MQ-01 | Exactly two active PostgreSQL instances in normal Compose startup | Migration and local runtime verification |
| MQ-02 | Existing accounting data copied without reset or overwrite; audit history retained | Seven table row-count/SHA-256 comparisons, schema checks, original audit backup comparison; old volume retained |
| MQ-03 | Broker runs inside processor and has no external connector | Explicit VM-only broker configuration; no network connector configured |
| MQ-04 | Source acknowledgment follows durable message acceptance | Dispatcher tests; failed publish leaves Primary hint pending |
| MQ-05 | JMS acknowledgment follows protected SQL commit | Real broker rollback and SQL-commit-before-ACK redelivery tests |
| MQ-06 | Restart, duplicate hints and bounded burst preserve work | Real broker restart, duplicate job, 200-message burst and handler Error regressions |
| MQ-07 | Existing security/financial invariants continue to hold | Recorded run: 83 Java tests and 15 PostgreSQL scenarios passed; final review added three passing worker-admission/shutdown regressions |
| MQ-08 | Offer 20 transfers/second for 120 seconds; all 2,400 uniquely complete | PASS: 2,400/2,400; 20.3091 steady completed TPS; zero configured tolerance; bounded backlog and balances passed |
| MQ-09 | Live Lab shows two physical DB panels and valid/forged outcomes | PASS: 13 real local integration checks; Node checks cover diagrams, authorization and evidence |
| MQ-10 | Architecture and compact sequence match implementation; production target is separate | Generated HTML/SVG and source-order/layout assertions |
| MQ-11 | Publication meets project content policy; no keys/data packaged | Content validation and explicit package allow-list |

An unchecked production item is not made complete by a passing demo benchmark.

## Recorded verification and review

The [September 6 measured run](../evidence/local-verification-2026-09-06.json), identified by `2026-09-06T23-27-43-c027aafb`, passed 83 Java tests in 11 suites, 15 PostgreSQL scenarios, and the 120-second load with zero tolerance. All 2,400 unique transfers completed with matching protected balances and durable audit outcomes. Steady-window completion was 20.3091 TPS; whole-run completion including warmup/drain was 19.9371 TPS. Drain took 378 ms. End-to-end p95 was 3,990 ms and p99 was 4,599 ms. These are local measurements, not a maximum capacity or latency SLA.

The [recovery run](../evidence/local-recovery-2026-09-06.json) passed fixture setup and two real JVM outage cases. The [Live Lab run](../evidence/live-lab-verification-2026-09-06.json) passed 13 checks. The [migration summary](../evidence/protected-migration-2026-09-06.json) records seven preserved table fingerprints. A separate read-only PostgreSQL inspection passed all 12 role-isolation assertions in `scripts/verify-protected-roles.ps1`.

Final source review found that an early failed worker Future could release batch admission while other tasks were still pending. The correction waits for every submitted task before propagating failure and preserves interruption. Three regression tests were added; subsequent Maven verification passed 86 tests. The earlier load report remains unchanged and is not relabeled as an 86-test run.

Development failures are retained, not hidden: one scenario run timed out scanning retained issuance history (fixed with bounded multi-page reconciliation); a load run stopped at 829 operations on backlog safety, with all 829 eventually completed and correct balances (followed by two bounded workers and faster queue draining); a compiler error was corrected before the passing run. The migration initially stopped on an equivalent PostgreSQL CHECK-expression cast representation; a narrowly validated resume completed without repeating the restore. No data reset was used to obtain a pass.

The measured p95 is higher than the September 5 baseline. Different history sizes and runtime configurations prevent an isolated broker-cost comparison. Do not claim that ActiveMQ made the system faster.

Presentation QA uses generated slide/PDF rendering and package geometry checks. HTML sequence fitting uses compact SVG dimensions and responsive source rules; no native-browser visual test is claimed. Fourteen editable slides and the eight-page article describe the same boundaries. Temporary renders stay under ignored `.local/artifact-qa/`; publication source generators are under `docs/build/`. Historical documents and old database volumes are retained rather than silently destroyed.

## Final post-review acceptance

The [final complete rerun](../evidence/local-verification-2026-09-06-final.json), `2026-09-06T23-48-18-1b9a3dcc`, passed all stages and restored normal mode: 86 Java tests in 12 suites, 15 PostgreSQL scenarios and all 2,400 unique transfers at 20 offered TPS for 120 seconds. Steady completed throughput was 20.0000 TPS with zero configured tolerance; whole-run throughput was 19.9173 TPS, drain 498 ms, p95 3,057 ms and p99 3,993 ms. All financial/evidence criteria passed. Missing unpublished receipt detection took 32.96 seconds within the explicitly extended 120-second test budget; it did not satisfy the former 30-second timeout. The primary-tampering incident check took 30.09 seconds.

The Evidence page and deck retain the earlier September 6 measured run as a dated example; this final report is additional evidence, not an overwrite. All 23 Node checks for the lab, migration metadata and teaching model passed. Project content validation and the allow-listed offline package validation passed. The reviewed PowerPoint has 14 slides, zero package/layout findings and zero geometry warnings; every slide and all eight PDF pages were visually checked through artifact rendering. Browser-native rendering and production failure modes were not tested.

## Migration and recovery

The explicit migration makes custom-format backups of Audit and legacy Settlement. It restores only seven non-conflicting accounting tables, without `--clean`, drops or truncation. It compares ordered row fingerprints, indexes and constraints and checks paired-posting balance. One reviewed PostgreSQL CHECK-expression cast rewrite is normalized narrowly; all other constraint differences fail. It validates original audit COPY data against the retained pre-migration backup before completing a resumed migration.

Startup refuses empty accounting initialization when a legacy Settlement volume exists without the protected schema marker. The former Settlement container is stopped and retained under the `legacy` profile. Backups, its volume, key files, receipts and local checkpoints are preserved. Once new writes occur, switching back to the old volume requires reconciliation of those writes.

Local credentials and backup contents stay in ignored `.local`; they must never enter a customer package. Public evidence may include only sanitized measurements and schema/table summary hashes.

## Production boundary

The production diagram shows independent key custody (KMS/HSM), service IAM/deployment governance, authenticated encrypted transport, protected database administration, independently retained recent checkpoints and recovery storage. Backups/PITR, retention policy, external security review and failure testing are deployment requirements, not properties provided by HMAC or this local broker.

Official references: [ActiveMQ Classic 6.3.2](https://activemq.apache.org/components/classic/download/classic-06-03-02), [VM transport](https://activemq.apache.org/components/classic/documentation/vm-transport-reference), [KahaDB](https://activemq.apache.org/components/classic/documentation/kahadb), [redelivery](https://activemq.apache.org/components/classic/documentation/redelivery-policy).

## Incident notification addendum

The notification extension retains the two-database design and the same protected execution authority. When the processor appends an `INTEGRITY_INCIDENT`, the same Audit/Protected SQL transaction inserts the first notification for that operation in `notification_outbox`. Reconciliation can produce additional audit evidence, but does not repeatedly enqueue the initial alert for the same operation.

```text
Integrity check -> Audit/Protected [audit_events + notification_outbox]
                                    -> asynchronous email dispatcher
                                    -> local Mailpit capture / configured SMTP
```

The dispatcher uses expiring claims, retries, finite SMTP timeouts and a send-rate cap. It sends minimal plain text only to configured recipients; transaction fields cannot redirect the notification. SMTP work is separate from verification/accounting, and SMTP outages neither authorize an invalid operation nor block financial execution. Protected database failures remain distinct from mail-service failures.

Mailpit is a loopback-only demo inbox, not another database of financial truth and not an external delivery service. A message addressed to a public email address remains in the capture inbox unless an independently configured real mail service is used. Direct Java startup has notification delivery disabled by default; the local launcher explicitly selects capture mode.

Delivery is at least once: SMTP acceptance followed by a crash before the delivered marker can produce a duplicate. This is not an inbox-delivery or human-response guarantee. Missing rows and source changes are suspected integrity discrepancies, not proof of fraud; incidents observed after execution do not undo or deny the earlier protected settlement. Historic incidents are not automatically backfilled into notifications.

See [incident notification setup, flow and operational limits](notifications.md). Production needs approved SMTP/TLS and sender identity, protected routing configuration, backlog/age and bounce monitoring, escalation/retention policies, and handling of distinct-operation floods. The recorded benchmark and acceptance sections above describe earlier revisions; they are not performance evidence for this addition. The README retains the latest strict 20 TPS failure rather than relabeling it as a pass.

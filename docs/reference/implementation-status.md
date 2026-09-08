# Implementation and Verifiable Results

## Current status as of September 8, 2026

The running design uses three Java services, **two PostgreSQL databases** (Main and Audit/Protected), embedded persistent ActiveMQ and an optional incident-email side channel. Audit/Protected retains authoritative balances and execution identity as well as independent evidence. The new `notification_outbox` commits the first alert for an operation with its integrity incident. A dedicated dispatcher retries minimal SMTP messages; mail availability does not authorize or gate a financial operation. Local Mailpit is a capture inbox, not Gmail delivery. See [architecture](architecture.md), [code structure](code-structure.md) and [notification guarantees](notifications.md).

| Evidence scope | Result and limitation |
|---|---|
| Latest throughput run, September 7 | **FAILED** combined/load acceptance: 19.9636 steady completed TPS below the strict 20 TPS threshold; all 2,400 transfers completed uniquely, zero transaction failures, correct accounting/audit and normal restoration. 92 Java tests and 15 PostgreSQL scenarios passed. [Sanitized report](../evidence/local-verification-2026-09-07.json). |
| Historical September 6 final run | **PASSED** at 20.0000 steady TPS, 86 Java tests and 15 scenarios. This is an earlier revision, not a replacement for the failed latest gate. [Historical report](../evidence/local-verification-2026-09-06-final.json). |
| September 8 notification implementation, before publication updates | 137 Java tests in 19 suites, 44 Node checks and 18 PostgreSQL role checks passed. [Detailed scope](notifications.md#verification-on-september-8-2026). |
| PostgreSQL-to-Mailpit notification run | Four cases passed: valid/no alert, forged/quarantined, repeated identity and post-settlement source tampering. No external delivery or new load benchmark. [Report](../evidence/notification-verification-2026-09-08.json). |

The latest throughput failure remains open; a successful functional notification test does not complete it. No production SLA, independently administered KMS/HSM, real payment rails, real-provider SMTP handshake, host power-loss resilience or third-party certification is established. Notification delivery is at least once; a crash after SMTP acceptance can produce a duplicate. Incident observation is not proof of fraud or automatic grounds to freeze a recipient.

## Historical September 5 implementation snapshot

The remaining sections preserve the original dated implementation/recovery record. References there to three databases, 75 tests or completed acceptance describe September 5 only; they are not current topology or current aggregate acceptance.

> September 6 topology update: [Main + Audit/Protected with embedded ActiveMQ](architecture.md) supersedes physical three-database and direct outbox-to-inbox descriptions below. Requirement IDs and historical evidence remain preserved. September 5 metrics describe the earlier topology only.

Date: 2026-09-05. This file updates the status from the original master plan without removing historical context.

## Historical September 5 Conclusion

**Core local acceptance has passed.** Standard `Maven clean verify`, three PostgreSQL instances, three JVMs, 15 functional/adversarial scenarios, 20 TPS load for 120 seconds, and return to normal mode produced a successful combined report. This verifies a local software-key demo; it is not production certification.

Main report: [verification-2026-09-05T22-34-15-fccf37b1](../../.local/verification-2026-09-05T22-34-15-fccf37b1/report.json), `passed:true`, completed 2026-09-05 22:38:50 UTC. All stages, including `normalRestart`, are `PASSED`.

For repository publication: [selected actual results without secrets or user-specific absolute paths](../evidence/local-verification-2026-09-05.json). It contains metrics, scenario results, and SHA-256 hashes of the original local reports. **Do not publish the entire `.local` directory**: it contains real local credentials and demo keys.

| Check | Measured result |
|---|---|
| Maven / JUnit engine | 75 tests, 10 suites, 0 failures/errors/skips |
| Node assertions for the load harness | 20 tests; include repeated UUIDs and incorrect postings |
| Node discovery / PowerShell 5.1 | 23 tests, all passed |
| Process ownership / PowerShell | 9 offline regressions; PID reuse and unrelated JARs are rejected |
| PostgreSQL attack scenarios | 15/15, [raw report](../../.local/verification-2026-09-05T22-34-15-fccf37b1/scenarios/report.json) |
| Load | 2400 submitted = 2400 unique = 2400 completed operations, 0 errors |
| Arrival / steady completion | 20 / 20.0091 TPS; completion window 10–120 seconds, tolerance **0%** |
| Whole-run completion including drain | 19.9181 TPS; final completions required another 493 ms |
| End-to-end latency | p50 490 ms; p95 709 ms; p99 853 ms; max 1129 ms |
| Financial reconciliation | Each operation: exact protected payload + two correct postings + durable audit outcome; all 8 balances matched |

[Load report](../../.local/verification-2026-09-05T22-34-15-fccf37b1/load/report.json), [every operation](../../.local/verification-2026-09-05T22-34-15-fccf37b1/load/operations.json), [queue samples](../../.local/verification-2026-09-05T22-34-15-fccf37b1/load/samples.json). Change in average queue size in the second half relative to the first: −0.703 operations. Measured on Intel i7-13620H, 16 logical CPUs, approximately 16 GiB RAM, Windows, Java 21.0.11, Node 24.19.0, Docker Engine 29.7.2. PostgreSQL image: `postgres:16-alpine`, digest `sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`.

120 seconds is a reproducible short load test, not proof of unlimited sustained capacity or a production SLA. A 10-minute soak and one million records were not measured. All transfers are simulated; no real money is involved.

### Actual Service Outages: PASS

Separate [recovery report for publication](../evidence/local-recovery-2026-09-05.json) and [original local report](../../.local/recovery-2026-09-05T22-44-50-412Z-ade2ba74/report.json): `passed:true`; both outage scenarios and normal recovery passed.

- **Processor stopped:** HTTP 503 after successful MAC issuance and publication does not mean the operation was not created. While the processor was off, no postings existed. After startup, the queue completed the original operation automatically; a retry with the same idempotency key did not create a second debit.
- **Key-service stopped:** a new operation received HTTP 503 without an issuance receipt, primary row, or balance change. After service startup, retrying with the same key completed once.
- Exact UUIDs, payload, idempotency keys, amounts/accounts on both postings, balances, and durable audit outcome were checked. After `finally`, all health endpoints returned HTTP 200, the fault hook returned HTTP 404, and all three PostgreSQL instances were healthy.
- Additional offline regressions passed: 9 ownership/process-list checks and 5 child-process lifecycle checks. The `scripts/recovery.ps1` wrapper runs them before each new recovery run.

These tests stop JVMs on one Windows host. Power loss, database destruction, production failover, audit/settlement DB unavailability, and key-service loss during an already in-flight verification operation were **not tested** by this run.

## Diagnostic History and Resolved Environment Limitations

Before the user enabled Full access, Maven downloaded PostgreSQL JDBC, but javac failed with `WindowsPath.toRealPath / AccessDeniedException` while closing a JAR. Docker CLI returned `Access is denied`. After changing the mode, commands ran under the user's ordinary account; Docker worked without reinstallation.

Full-run command from the project root in the user's ordinary PowerShell:

```powershell
.\scripts\verify-local.ps1 -DurationSeconds 120
```

Clarification after the user's attempt: execution stopped **before the build** because Node was absent from the ordinary PowerShell PATH. Node was already installed (runtime 24.19.0 verified). The script was fixed to discover an existing runtime outside PATH, validate version 20+, and record the selected path/version in the report. Node was not installed again and PATH was not persistently changed. When `.ps1` execution is disabled, use the previously agreed `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\verify-local.ps1" -DurationSeconds 120` command in a separate process. At that historical point, full acceptance still required a rerun after this fix; the old `passed:false` report was retained.

The user's next attempt (`verification-2026-09-05T21-52-55-57ab691d`) found Node successfully but stopped at Maven clean: disposable `tokenization-module/target/smoke-vault-*` fixtures from an old manual diagnostic runner had Windows ACLs restricted to `CodexSandboxOffline`. The `target` directory itself belonged to user `vikto`; mixed ownership prevented user cleanup. These were agent test artifacts, not working keys in `.local/keys`.

Four exact names were checked: `smoke-vault-13240372867439123502`, `smoke-vault-17397767122119290311`, `smoke-vault-3327505297732932027`, and `smoke-vault-8462923175111283594`. Targeted removal from their owner's context was stopped by execution policy **before the command ran** (`sandbox_approval=false`); folders were not removed and ACLs were not weakened. Maven clean and real-vault protection were preserved. A separate temporary-directory leak in the ordinary HTTP integration test was fixed using `@TempDir` plus Spring-context closure through `@DirtiesContext(AFTER_CLASS)`; the normal rerun of that test was still pending at that point. Manual reflection runners should not be rerun: they bypass JUnit lifecycle.

Subsequent fix: a confirmed standard UAC launch restored permissions **only on the four listed disposable fixtures**, which were then removed. Working keys and databases were untouched. `KeyServiceIntegrationTest` with `@TempDir` passed twice under the normal JUnit lifecycle; Maven clean also passed again.

First actual run, `verification-2026-09-05T22-28-58-74c61d31`: 75 Java tests, 15 scenarios, and 2400/2400 load operations passed (20.0273 steady TPS), but overall `passed:false` because the final restart failed. In PowerShell 5.1, `@(ConvertFrom-Json ...)` nested the PID list inside another array. Fixed with typed list reading; PID/start-time/JAR checks were not disabled. Owned JVMs are now also stopped **before** Maven clean, so Windows does not retain locks on executable JARs. The second full run above confirmed these fixes.

The first additional recovery run, `recovery-2026-09-05T22-38-58-108Z-65781d8c`, was aborted as unsuccessful: the Node harness waited for `close` after the PowerShell launcher exited while a background process still held pipe handles. The original `report.json` and `interruption.json` were retained; only the stuck harness was stopped. The new launcher distinguishes `exit` from `close` and bounds post-exit output draining to 250 ms without stopping JVMs. A regression reproduces the problem through actual PowerShell `Start-Process`; the next recovery run above passed completely.

For extended acceptance: `-DurationSeconds 600`. Results are in `.local/verification-*/report.json`, with raw scenario/load reports alongside. Do not share files containing secrets. The script preserves databases, keys, and evidence; test accounts and operations remain as labeled fixtures. After scenarios it restarts the demo without fault-injection hooks.

## Implementation Map

### Component Checks Already Performed

Agents directly invoked existing JUnit test methods with real assertions/lifecycle fixtures: **75 passed** (15 protocol/key-service; 36 processor/audit/settlement; 24 main API). These included 17 HTTP integration tests with actual Spring Boot/Tomcat and H2; the remainder were unit/JDBC/service tests, including concurrency and rollback. Dependent external services were replaced by HTTP fixtures/mocks in some tests.

This was a fallback diagnostic method in a restricted environment, **not a Maven Surefire/JUnit-engine result and not a PostgreSQL run**. Temporary runners are in ignored `target`; ordinary `clean verify` rebuilds source and runs tests normally. A separate `surefire:test` invocation before standard test compilation returned `No tests to run` — this does not count as successful acceptance.

The historical fallback checks above are retained for transparency. They are superseded as acceptance evidence by the current normal `BUILD SUCCESS` and fresh Surefire XML: 75 tests. PowerShell parsing, Node syntax checks, and `git diff --check` also passed.

| Requirement | Implementation | What acceptance checks |
|---|---|---|
| Unambiguous canonicalization | `CanonicalEncoder`, version 1, TLV with uint32 big-endian length; UUID16, int32/int64, null tag | Golden bytes/hash/HMAC; changes to every protected field |
| Time and money | UTC epoch microseconds int64; USD cents int64; no float/string coercion | Microseconds are preserved; fractional amounts/strings rejected |
| HMAC and rotation | Separate key-service, software key store, `keyId` inside MAC | Issuance/verification use different credentials, unknown key, historical verification after rotation |
| Independent expected state | Commit issuance receipt before returning MAC; independent inventory | Forgery without receipt, row/hint deletion, completed-operation changes |
| Request retries | Ledger-scoped idempotency key and business-field fingerprint | One ID for identical retries; conflicting parameters; concurrent retry |
| Protected execution | Separate settlement DB; account locks; TransactionTemplate; unique journal | No partial debit; no duplicate debit; concurrent overspend rejected |
| TOCTOU | Reread immediately before execution; use checked snapshot fields | Change/deletion during test pause does not change funds |
| Durable delivery | Primary outbox → protected inbox/lease → outcome outbox → audit/projection | ACK only after durable inbox; failure/retry without loss or duplication |
| Funding | FUNDING from a dedicated demo treasury, using the same MAC/settlement path | No bypass through `initialBalanceCents`; treasury balance may be negative as a simulation |
| Correction | REVERSAL of the original completed operation + new CORRECTION | Original record unchanged; link included in MAC; repeated compensation prohibited |
| Audit | Sequence, unique events, Merkle inclusion proof, Ed25519 checkpoint | Leaf/root tampering, tail deletion, rollback relative to retained anchor |
| Quarantine | Invalid operation → QUARANTINED, no postings | Recipient is not automatically blocked solely because a row was tampered with |
| Database separation | 3 PostgreSQL instances, different admin/runtime credentials and SQL grants | Primary admin has no audit/settlement privileges; runtime cannot modify immutable tables |
| 20 TPS | Fixed arrivals at 20/s; wait for COMPLETED + outcomeRelayed + 2 postings | All operations complete; balances reconcile; queue does not grow; p50/p95/p99 and duration |

Protocol v1 permits restricted ASCII in textual identifiers. Unicode NFC is therefore not applied silently: unsupported identifiers are rejected. Account name is metadata and is not part of the financial operation. Details and test vectors: [PROTOCOL.md](protocol.md).

## Claims This Version Does Not Support

- Software keys on one computer are not a cloud KMS/HSM. The key-service API is implemented; a real provider adapter is still absent.
- Local anchor files outside audit DB are not an independent timestamp authority, WORM, or a separate security account. An external verifier must obtain and pin the public key through a trusted channel.
- An Ed25519 signature confirms possession of the signing key; by itself it proves neither user authorization, business-event truth, nor regulatory compliance.
- Merkle inclusion proofs are implemented; a compact consistency-proof API and incremental tree are not. Full reconciliation and checkpointing recompute the prefix/tree in memory.
- Deletion checking is periodic through an independent inventory. It has scan delay and a publication grace window (5 seconds); it is not synchronous prevention of DELETE.
- Correction of an unfinished operation through atomic cancellation/supersession is absent in this iteration. A completed operation is corrected only by separate compensation and replacement; these are two financial operations, not a distributed atomic rollback.
- No recovery run after destruction of the entire trust domain, real payment rails, independent security review, compliance certification, or production SLA.
- The Spring Boot 3.3.5 baseline is inherited. Before external deployment, update to a supported runtime/dependency baseline, perform a vulnerability/SBOM review, and repeat full acceptance. Releases are checked against [official Spring](https://spring.io/blog/category/releases); the existence of a newer release is not evidence that an upgrade was performed.

## Acceptance and Honest Metrics

- [x] Recorded target load: **20 transactions per second**, not per minute.
- [x] Separate services, SQL schemas, local scripts, unit/component tests, and load harness written.
- [x] Complete ordinary Maven verify build without environment failure.
- [x] Functional/attack scenarios passed on actual PostgreSQL.
- [x] 20 TPS confirmed in a 120-second run with raw evidence and zero tolerance in the steady window.
- [ ] Extended 10-minute soak / scale to one million records.
- [x] Recovery after actual processor and key-service stop/restarts verified in the scenarios described above.
- [ ] Power loss, database outage, backup/PITR, and production failover exercises.
- [ ] Supported dependency baseline updated and checked before public deployment.
- [ ] Independent technical review / presentation rehearsal.

Default harness tolerance is 2% for a finite measurement window (20 offered TPS versus delayed durable completion). This is **not** an allowed loss rate: every request must complete correctly. `-RateTolerance 0` requires the strict measured threshold. The report must show actual values, not just PASS/FAIL.

Locking behavior and deadlock risk were checked against [official PostgreSQL documentation](https://www.postgresql.org/docs/current/explicit-locking.html). Ordering locks reduces risk but does not replace concurrency tests and retries after failures.

# Understanding and Presenting the Transaction Integrity Demo

A practical guide for the author, updated September 8, 2026. The latest throughput gate failed; earlier successful measurements remain dated historical evidence. This prepares a technical discussion, not a production payment-system deployment.

## Current evidence and what not to conflate

The [latest load run, September 7](../evidence/local-verification-2026-09-07.json), completed all 2,400 unique transfers correctly with zero transaction failures, but measured **19.9636 steady completed TPS**, below the strict 20 TPS threshold. The load gate and combined report are **FAILED**. Its 92 Java tests, 15 PostgreSQL scenarios, protected accounting, durable audit and normal restoration passed. p95 was 668 ms and p99 791 ms. Do not round the result into a pass or attribute the failure to a cause that was not isolated.

The [historical September 6 final run](../evidence/local-verification-2026-09-06-final.json) passed with 86 Java tests, 15 scenarios and 20.0000 steady completed TPS. The [initial September 6 measurement](../evidence/local-verification-2026-09-06.json), [recovery](../evidence/local-recovery-2026-09-06.json) and Live Lab evidence remain dated examples, not acceptance of later source changes.

The September 8 notification implementation passed 137 Java tests in 19 suites, 44 Node checks and 18 PostgreSQL role checks before publication updates. Its [four-case PostgreSQL-to-Mailpit integration](../evidence/notification-verification-2026-09-08.json) verified valid/no-alert, forgery/quarantine, repeated identity and post-settlement tampering. This is local capture, **not delivery to Gmail**, a real-provider SMTP handshake or a new throughput run. Read [notification setup and limitations](../reference/notifications.md) before demonstrating the inbox.

Missing-record reconciliation scans retained history. A 30-second scenario timeout was observed; the operation was later quarantined without postings. The long-history test now declares a 120-second observation budget. Do not present either value as a production detection SLA. The historical quotations below retain their original date and measurements.

## 1. Open these three resources first

1. [Interactive HTML explanation](../index.html) starts with four short overview blocks. Architecture follows twelve numbered arrows across three modules, two databases and embedded ActiveMQ; Sequence shows separate participant lifelines. These diagrams explain implementation order, not captured network traffic.
2. [Latest failed throughput report](../evidence/local-verification-2026-09-07.json), [historical pass](../evidence/local-verification-2026-09-06-final.json) and [notification capture report](../evidence/notification-verification-2026-09-08.json) describe different runs. Opening a report does not repeat the experiment.
3. [Current and production architecture](../reference/architecture.md) maps what works today and what needs additional infrastructure. The [implementation status](../reference/implementation-status.md) lists detailed limitations.

The separate [Live transaction lab](../live/index.html) calls the actual local Java services and reads actual PostgreSQL rows through a loopback-only helper. Opening its file alone does not start that helper. Explain the distinction during a meeting: "First I will explain the architecture, then run a valid transfer and a forged database instruction against the local stack, and finally show the retained benchmark." All funds are simulated; this is not a bank integration.

## 2. A 90-second explanation

> Imagine an attacker obtains full access to the primary transaction database. They can change the amount, recipient or status, or insert a new row. Our task is to prevent that row from becoming an unauthorized debit.
>
> The trusted application sends an operation to a separate service. That service computes an HMAC, an authentication code using a secret key, and commits an independent receipt of the exact content it authenticated before returning the response. The key does not reside in the primary database.
>
> The processor verifies the code and receipt, compares the source record again immediately before execution, and consumes only the checked snapshot. Our demo's authoritative balances and postings reside in a separate protected database. Both postings and execution state commit together. Repeating a request does not cause a second debit.
>
> Audit history also receives digitally signed Merkle checkpoints. We do not build blockchain consensus among mutually distrustful participants.
>
> A suspected integrity incident also queues a minimal email in protected storage. A separate worker retries delivery; email is not permission to settle. This demonstration uses a local capture inbox, not an external mailbox.
>
> The latest throughput run completed all 2,400 transfers correctly, but 19.9636 completed TPS did not pass our strict 20 TPS threshold. Notification tests are functional evidence, not a new benchmark. The setup does not resist the administrator of the whole computer. Cloud isolation, hardware keys and independently retained evidence remain separate production requirements.

Main point: **a primary database row is a candidate for execution, not permission to move money**.

## 3. Inside one transfer

In this example, the sender transfers 25 USD to the recipient. The operation records the amount as `2500` cents rather than a floating-point number.

1. **Application, port 8080.** The integrating application owns user identity and permission to transfer funds. Our local caller has a service credential. A complete end-user Identity Provider is not implemented.
2. **Key service, port 8081.** It produces unambiguous operation bytes and an HMAC. It commits the issuance receipt in audit DB before returning the result. A retry with the same idempotency key and business fields returns the original operation.
3. **Primary DB, port 55431.** The application commits the operation and source outbox hint in one SQL transaction. This database cannot grant execution authority: its administrator is the attacker in our model.
4. **Processor, port 8082, with embedded ActiveMQ.** Its dispatcher polls Primary outbox, persists a UUID message, and then acknowledges the hint. Its consumer commits a protected SQL job before acknowledging JMS delivery. It discovers candidates and checks the independent receipt, HMAC and ledger binding. Before settlement, it rereads primary and compares it with the checked snapshot. Modification or absence causes quarantine without financial postings.
5. **Audit/Protected DB, port 55432, accounting role.** The checked snapshot supplies the amount and accounts. The processor locks the accounts, checks protected balances and restrictions, and atomically commits debit, credit, balances, result and outcome outbox. An existing result prevents repeat execution.
6. **The same Audit/Protected DB, audit role.** It receives the durably relayed outcome. Primary receives a displayable status. Both projections may lag. There is no single ACID transaction across both databases and JMS.
7. **History.** Audit events form a Merkle tree. The key service signs a checkpoint using a separate Ed25519 key. Anchors reside outside audit DB but remain on the same local computer.

An incident follows an optional side path rather than an extra normal settlement step: `audit_events + notification_outbox` commit together, then a separate dispatcher sends the initial per-operation alert. For the local demonstration inspect `http://127.0.0.1:8025`. Repeated observations retain one notification entry, but a crash after SMTP acceptance can still cause duplicate email. A discrepancy after settlement does not mean the original protected operation was blocked or reversed.

Funding uses a protected `FUNDING` operation from the demonstration Treasury account, rather than an initial-balance edit. This simulates funds and does not prove an external bank deposit. Correcting a completed transfer means a separate compensation and a new correct operation, rather than changing the original row.

## 4. Terms without unnecessary mathematics

| Term | Explanation and boundary |
|---|---|
| HMAC-SHA256 | A content authentication code using a secret key. Changing the data and recomputing an ordinary hash is insufficient. It neither encrypts data nor supplies a public signature. |
| Canonicalization | One exact method of converting fields into bytes. Version 1 specifies a field ID, type, byte length and value in fixed order. Time uses UTC Unix epoch microseconds. Unsupported Unicode in identifiers fails validation. |
| `schemaVersion` | The encoding-rule version. Rules cannot silently change while historical records are expected to remain verifiable. |
| `keyId` | A nonsecret identifier for an exact key. It enables historical verification after rotation and is itself bound by the MAC. |
| Issuance receipt | An independent receipt for an authenticated issued operation. It does not prove that the row later appeared in primary or that the transfer executed. |
| Trust domain | A scope with distinct access and management controls. Three containers under one administrator do not equal three independently governed organizations. |
| Settlement | Protected execution: authoritative balances, postings, account controls and records of completed operations. The money in this demo is simulated. |
| TOCTOU | Time-of-check to time-of-use. Data may change between verification and use. A final comparison and execution of that exact checked snapshot prevent substitution of the financial fields. |
| Idempotency | An identical retry returns the prior result without another financial effect. The same identifiers with different business data cause a conflict. |
| Outbox / inbox | Durable records of work to deliver and work accepted for processing. They survive restart. Repeated delivery is expected and handled without a duplicate financial effect. |
| Merkle tree | A hash tree binding ordered audit events to a root hash. An inclusion proof establishes membership in a particular tree, not the truth of the event. |
| Checkpoint / anchor | A checkpoint binds the root, tree size and log identity to context and a signature. An anchor is a retained reference point for later comparison. Freshness needs a trusted recent expectation. |
| Ed25519 | An asymmetric checkpoint signature. A private key signs and a public key verifies. The expected public key must come from a trusted channel, not merely accompany the signature. |
| Quarantine | The operation does not execute, and the discrepancy remains available for investigation. This does not automatically block the named recipient. |
| Mailpit / SMTP acceptance | Mailpit captures test email locally. SMTP acceptance records that a mail server accepted the message; it does not prove inbox delivery, reading or a human response. |

Exact field layout and test vectors: [protocol v1](../reference/protocol.md). Extended explanations, including KMS/HSM and regulatory abbreviations: [technical glossary](../reference/glossary.md).

## 5. An 18-minute rehearsal

| Time | What to show | What the audience should understand |
|---|---|---|
| 0–2 minutes | Overview: problem, control, execution and limits | The boundary protects execution under primary compromise, not the entire computer. |
| 2–6 | Architecture arrows, then the full Sequence diagram | A receipt precedes primary publication. Polling persists work before acknowledging it; receipt lookup precedes source validation. |
| 6–9 | Live lab: prepare accounts, valid $25 transfer, forged $25 row | Real receipts, jobs, results and postings explain what ran. The forgery has no receipt and stops before HMAC verification. |
| Optional within 6–9 | Open the local Mailpit inbox and correlate the fresh forgery alert | The protected incident creates a reporting side channel, not execution authority; no real Gmail delivery is claimed. |
| 9–12 | Retry the same valid action; inspect balances and two postings; explain the final source check | Repeating an action does not debit twice. A VERIFIED flag is insufficient; settlement consumes the exact checked snapshot. |
| 12–14 | Actual JSON results | These measure a specific local setup, not the capacity of a payment network. |
| 14–16 | Production architecture | KMS/HSM, independent authority and evidence retention are explicit requirements beyond the local demo. |
| 16–18 | Two or three questions from the section below | Implemented mechanisms, measured evidence and planned controls remain distinct. |

For a five-minute talk, retain Overview, the numbered architecture, the valid/forged outcomes and carefully qualified metrics. Modification, deletion, TOCTOU and service-outage scenarios remain available in the separate test harness, not as Live Lab buttons. Rehearse real service restarts separately because they deliberately interrupt availability.

## 6. Running the real local setup safely

These commands apply only to this local project copy. Docker Desktop with Linux containers, Java 21, Maven and Node 20+ are required. The scripts discover available local runtimes. `Bypass` applies to the launched PowerShell process and does not change persistent system execution policy.

```powershell
Set-Location "C:\path\to\record-integrity"

# Start already-built JARs and inspect service status.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start.ps1" -SkipBuild
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\status.ps1"

# Start the live lab in its own foreground terminal; stop it with Ctrl+C.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\live-demo.ps1"
# Open http://127.0.0.1:8090/docs/live/index.html

# Full acceptance: build, tests, adversarial scenarios and 20 TPS load.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\verify-local.ps1" -DurationSeconds 120 -RateTolerance 0

# Run separately after acceptance: real stops of two JVM services.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\recovery.ps1"
```

`-DurationSeconds` sets load duration, not arrival frequency. Acceptance uses **20 transactions per second**, not per minute. A full run takes longer than two minutes because it includes the build, scenarios and restart. A build failure may leave services stopped. Inspect the overall `passed` field and `normalRestart` stage, not just the last console line.

The Live Lab creates dedicated accounts, funds Alice with an authenticated $1,000 operation and offers a fixed $25 transfer or direct-primary forgery. Repeated actions reuse persistent identifiers; they do not create another debit. After both experiments pass, **New demo accounts** allocates fresh identities for another presentation and retains the old records. Click Prepare to fund the new accounts. It has no arbitrary SQL input or service-fault controls. Observation access to both databases belongs to the trusted helper, not the simulated attacker. Each store is read separately and can temporarily lag another; missing evidence never counts as success. Tags and key IDs are visible metadata, not secret key bytes.

Load and functional checks create their own fresh accounts and operations with simulated funding. One adversarial scenario deliberately deletes a primary row it created. Independent receipts and audit evidence remain. The scripts do not remove volumes or unrelated data. Recovery requires exclusive use of the setup. Do not run it alongside a presentation, load test or another acceptance run.

Full acceptance temporarily enables test fault hooks and then restores normal configuration. Recovery also checks restoration. `start -SkipBuild` alone does not change the configuration of JVMs already running. After manually interrupting tests, restore normal mode explicitly:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\stop.ps1" -KeepDatabases
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start.ps1" -SkipBuild
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\status.ps1"
```

Never share `.local` with customers: it contains secrets, keys and local data. Use the prepared public JSON under `docs/evidence`, documents and HTML for discussion. Do not expose the local APIs publicly. Full instructions: [docs/reference/running.md](../reference/running.md).

## 7. Reporting the historical baseline accurately

The quotation below describes the September 5 three-DB version without ActiveMQ. For current results use the [topology update and evidence](../reference/architecture.md).

"On a Windows setup with three PostgreSQL instances and software keys, 75 Java tests and 15 scenarios passed. The 120-second experiment completed all 2,400 unique transfers without failures. After ten seconds of warmup, it measured 20.009 completed TPS with zero configured threshold tolerance. Whole-run throughput including warmup and final drain was 19.918 TPS. The p95 latency to durable completion was approximately 709 ms."

Completion means more than HTTP 202. The harness checks `COMPLETED`, `outcomeRelayed=true`, exact protected operation fields, both postings and balances. The measurement does not establish unlimited sustained operation. A ten-minute soak and one million records have not yet been tested.

A separate recovery run confirmed two cases: after restart, the stopped processor handled an already published operation without a second debit. A stopped key service refused new issuance, and retry after recovery completed once. These tests do not cover power loss or PostgreSQL failure.

## 8. Twelve difficult questions

**1. What if the DBA simply writes COMPLETED?** Primary status is a projection. Settlement requires an authenticated operation and protected execution state. An arbitrary status does not move funds.

**2. Can an attacker steal a valid MAC and change the recipient?** Recipient, amount, operation identity, ledger, type, time, correction reference and other required fields belong to the canonical bytes. The changed snapshot will not match the MAC and independent receipt.

**3. What if the entire row disappears?** A standalone HMAC cannot detect that. The service retains an independent receipt before returning issuance, and inventory scanning reveals the missing record. Detection includes scan delay and a five-second grace measured from the authenticated operation's creation timestamp, not a guaranteed period after publication. The result is a discrepancy, not automatic proof of malicious intent.

**4. What if the row changes immediately after the final check?** Settlement uses the checked snapshot already selected, not a new amount or recipient read from primary. The modification cannot change the postings. Later reconciliation detects the discrepancy. The design does not claim a primary lock throughout the entire settlement transaction.

**5. Can a client retry if the response disappears?** Yes, using the same idempotency key and business fields. HTTP 503 does not always mean nothing persisted: primary publication may already have committed when the processor is unavailable. The retry binds to the original operation. A different payload causes a conflict.

**6. Why not block the recipient automatically?** An attacker can name an innocent account to cause denial of service. The operation enters quarantine automatically. Account restrictions use separately protected manual holds and investigation.

**7. Who checks the user's right to transfer funds?** The integrating application. The module checks issuance/verification service authority and content integrity. HMAC does not prove the user's identity or business entitlement. Trusted-application compromise lies outside this threat model.

**8. Where are the keys, and can the verifier generate MACs?** The key service currently uses OS-protected software files. The processor gets a verification-only API, without the secret or issuance permission. The common host administrator remains trusted. Production needs genuinely independent key governance.

**9. Why use both HMAC and Ed25519?** HMAC authenticates operations internally. Ed25519 signs Merkle audit checkpoints. They protect different objects. A signature does not turn local clock time into an independent trusted timestamp or prove that real money exists.

**10. Does Merkle automatically prove the entire history?** No. Inclusion establishes membership in a particular root. Rollback detection needs a trusted retained checkpoint. Freshness needs a sufficiently recent independent expectation. This demo has local anchors, full-tree reconstruction and no compact consistency-proof API.

**11. Does a correction atomically cancel the past transfer?** No. A separate REVERSAL compensates a completed transfer, followed by a CORRECTION referencing the original. These are separate financial operations with normal settlement checks. Atomic cancellation of an operation that has not yet executed is absent from this version.

**12. What is new here, and can a bank integrate tomorrow?** The contribution is engineering: reproducible integration of established mechanisms and execution boundaries around relational data, without claiming new cryptography. A bank would need adaptation to real settlement, external funding evidence, independent IAM/KMS, recovery, dependency maintenance and security review. Lower cost, regulatory certification and universal protection have not been established.

## 9. Code map and pre-presentation checklist

Read in this order. Related tests reside in `src/test/java`:

- [TransferService.java](../../account-transfer-app/src/main/java/com/demo/transferapp/service/TransferService.java): operation creation, issuance, publication, funding and reversal/correction.
- [CanonicalEncoder.java](../../tokenization-module/src/main/java/com/demo/integrity/crypto/CanonicalEncoder.java): the exact authenticated bytes.
- [IssuanceService.java](../../tokenization-module/src/main/java/com/demo/keyservice/service/IssuanceService.java): receipt before response, retries and inventory. [LocalKeyVault.java](../../tokenization-module/src/main/java/com/demo/keyservice/vault/LocalKeyVault.java): keys, rotation and signatures.
- [Processor.java](../../transaction-security-module/src/main/java/com/demo/securityapp/service/Processor.java): `discover/reconcile`, `process`, `settle` and `relay` cover discovery, verification, settlement and outcome delivery.
- [AuditLog.java](../../transaction-security-module/src/main/java/com/demo/securityapp/audit/AuditLog.java) and [MerkleTree.java](../../transaction-security-module/src/main/java/com/demo/securityapp/crypto/MerkleTree.java): history, checkpoints and inclusion proofs.
- [NotificationDispatcher.java](../../transaction-security-module/src/main/java/com/demo/securityapp/notification/NotificationDispatcher.java) and [SmtpNotificationSender.java](../../transaction-security-module/src/main/java/com/demo/securityapp/notification/SmtpNotificationSender.java): separate claimed delivery, retry and minimal email content. `AuditLog` owns atomic incident/notification creation.

The [source layout guide](../reference/code-structure.md) distinguishes API DTOs, protocol models and services. The deck's embedded source citations reflect its generation-time layout; use this guide for current clickable source paths.

- [scenarios.mjs](../../scripts/scenarios.mjs), [load.mjs](../../scripts/load.mjs) and [recovery.mjs](../../scripts/recovery.mjs): reproducible evidence.

Five words to remember: **operation → receipt → snapshot → settlement → evidence**.

Open the HTML and JSON before the meeting, check normal mode, hide notifications that could expose secrets, and rehearse the scope limitation. Avoid claims such as "impossible to compromise," "blocks DELETE," "full KMS," "cheaper than competitors," "SEC/FDA compliant," or "20 TPS under every condition." A credible technical answer shows exactly where the current evidence ends.

## 10. Why the queue and only two databases?

Audit/Protected holds two logical responsibilities: independent evidence and authoritative execution. Keeping separate SQL roles reduces unnecessary permissions. Moving balances into attacker-controlled Main would weaken the guarantee, while moving Java calculations into database procedures is unnecessary.

Embedded ActiveMQ buffers persistent operation identifiers and retries delivery. It does not contain trusted payment instructions, generate HMACs, or guarantee high availability. It runs in the processor JVM. An external broker is a production option, not a deployed demo component. `OperationDelivery` isolates the adapter, but Kafka is not a drop-in configuration switch.

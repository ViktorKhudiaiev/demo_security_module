# Article Review: Alignment with the Implementation and Final Plan

Review date: 2026-09-05.

Document: Verifiable Record Integrity Without a Blockchain, Viktor Khudiaiev, September 2026, 9 pages.
Original file: C:/Users/vikto/Downloads/Verifiable_Record_Integrity_Without_a_Blockchain.pdf
Original PDF SHA-256: `cda1b8c5b510d25970d91f2b11d63c73a504f5647184ee7dd12aeb82084e2b6c`.

## Conclusion

**In its current form, the article does not fully match either the implemented demo or the adopted production plan. Recommendation: revise it before publication or submission to a technical program committee.**

The core idea is explained clearly and is largely correct. However, the text describes an earlier architecture. In one place, it proposes an insufficient condition for permitting financial execution; elsewhere, it understates mechanisms already implemented or conflates HMAC properties with guarantees of the entire system.

This is a conclusion about the article, not a claim that a new defect has been found in the working code. In particular, the unsafe VERIFIED wording on page 6 already fails to reflect the protection present in the implementation.

The review covered the full text and images of all nine pages, protocol/MAC-issuance/processor/audit code, the current architecture, the historical master plan and its update, and retained JSON reports. Tests were not rerun. The original PDF and project code were not changed; only this report was created.

Supporting documents: [current status][status], [current architecture][arch], [protocol][protocol], [master plan][plan]. Historical master-plan sections should not be mistaken for an inventory of the new code.

## 1. What can be retained

- Define the threat model first, then select the cryptographic mechanisms.
- A model with trusted services does not necessarily require distributed consensus.
- HMAC is computed over unambiguous bytes; undelimited concatenation of variable-length fields is dangerous.
- This implementation represents monetary amounts as integer minor currency units.
- The canonicalization version, key identifier, and reference to the corrected operation must be protected.
- Corrections preserve the original operation and add new records.
- An individual HMAC does not prove completeness of the record set.
- A missing auto-increment ID does not independently prove deletion: PostgreSQL does not reclaim sequence values used by a transaction that rolls back. The [PostgreSQL documentation](https://www.postgresql.org/docs/current/functions-sequence.html) confirms this.
- HMAC is not encryption, protection against database destruction, or independent evidence that a business event is true.
- Backups/PITR, independently retained evidence, and access management are separate layers.
- The absence of claims about a new cryptographic primitive or automatic regulatory certification should be preserved.

The quotations and meaning of the NIST definition references on page 1 align with [NIST SP 1800-26A](https://www.nccoe.nist.gov/publication/1800-26/VolA/index.html), which also references SP 800-12 Rev. 1. Citing NIST does not mean our module supplies every property listed there, including non-repudiation.

## 2. Required substantive corrections

### R01. Page 6: the existence of VERIFIED does not authorize execution

**Priority: critical for the technical description.**

Section: Two stores, two trust domains.

The article permits a financial action when a corresponding verified record exists. The following paragraph assumes that changing the source row means the corresponding verification record no longer exists. That is incorrect: an old verified record can remain after the source data is replaced.

This condition leaves a TOCTOU gap: data was correct when checked, then changed before use. Adding a second database does not close that gap on its own.

The code already does more:

1. It obtains an independent issuance receipt.
2. It checks the exact SignedOperation match, HMAC, and ledger domain.
3. Immediately before execution, it rereads the source record and compares it with the checked snapshot.
4. Only that snapshot's fields are passed to settlement. There is no later read of an untrusted amount/recipient for the financial action.
5. Protected execution accounting prevents retries from creating another financial effect.

Support: [Processor.java, verification and snapshot][processor-check].

Suggested wording:

> Processing is permitted only for the exact authenticated operation, matched to an independently committed issuance receipt. Immediately before settlement, the processor rechecks the source and executes the checked snapshot; a VERIFIED flag or the existence of a verification record is never sufficient on its own.

Important: primary tampering after the last comparison does not change the operation fields already selected for execution. Do not promise an atomic lock against every primary change for the entire settlement period.

### R02. Pages 2 and 6: the protected execution boundary is missing

**Priority: high.**

The current architecture contains three PostgreSQL instances:

| Store | Purpose | Trust in the threat model |
|---|---|---|
| Primary | Operations, outbox hints, and status projections | The attacker has full administrative access |
| Audit | Independent issuance receipts and ordered audit evidence | Protected from the primary administrator |
| Settlement | Authoritative balances, holds, postings, execution results, and durable outboxes | Protected from the primary administrator |

Keys and checkpoint files additionally reside on the trusted local host, outside these databases.

Both postings, balances, the execution marker, and the outcome outbox are saved in one settlement transaction. No shared ACID transaction across all three databases is claimed; results are delivered through retries and deduplication.

Support: [Processor.java, settlement][processor-settle], [architecture][arch].

Replace "a successful attack now requires two compromises" with a bounded guarantee:

> Administrative access to the primary transaction database alone is insufficient to execute forged or replayed operations, provided the trusted application, integrity services, evidence store and settlement domain remain uncompromised.

Compromise of the processor, settlement, shared host, or trusted MAC-issuance authority is a different threat model. Security cannot be established simply by counting databases.

### R03. Pages 5 and 8: deletion detection describes a different architecture

**Priority: high.**

The actual operation MAC contains neither a sequence nor the previous operation's MAC. The issuance-inventory sequence is a traversal cursor; it may have gaps and does not independently provide cryptographic proof of completeness.

The actual design:

- The key service commits an independent issuance receipt before returning the MAC to the calling application.
- The processor traverses that independent list and reconciles it against primary.
- It can therefore detect a missing issued operation even if its outbox hint was never observed.
- Separately, audit history is protected by a Merkle tree, signed checkpoints, and anchors retained outside the audit database.

Support: [IssuanceService.java][issuance], [protocol: issuance and retries][protocol], [Processor.java: reconciliation][processor-inventory].

The final plan selects Merkle audit with signed checkpoints. A MAC chain remains an explanatory alternative, not the next mandatory implementation step.

Limitations to retain:

- Missing-record detection is periodic, with traversal latency and a five-second publication grace window.
- "Issued but absent" is a discrepancy, not proof of malicious intent: failure before publication can look the same.
- Even a sequence supplied by a separate service requires defined issuance rules, accounting for failed publication, and trusted expected state.
- An internally valid old prefix does not prove that the presented log is the latest one.
- A local anchor is independent of the database, but not of the shared host.
- Remove "guaranteed" without its conditions and detection period.

### R04. Pages 4, 7, and 8: the second cryptographic layer is missing

**Priority: high.**

Three different objects must be distinguished:

| Object | Actual protection | What must not be promised |
|---|---|---|
| Immutable operation | HMAC-SHA256 plus an independent issuance receipt | Public proof through HMAC |
| Settlement outcomes and account-management events in the audit log | Merkle leaves and Ed25519-signed checkpoints | HMAC on every state event |
| Signed checkpoint | Public signature verification with a trusted key; inclusion proof | An independent TSA, public witness, or guaranteed freshness |

The code uses Ed25519 to sign a dedicated canonical checkpoint representation, not the same operation record. It binds log identity, tree size, root, key, version/context, and time.

The page 4 statement that state-change events use "the same mechanism" is inaccurate. Primary status projections are not settlement authorization. Not every internal job/lease/retry transition becomes a separate immutable event.

The page 8 statement that it "does not provide publicly independent verification" should apply specifically to HMAC. Signature and inclusion verification are already possible with a trusted key, but there is no independent public witness or external time assurance.

Support: [AuditLog.java][audit], [checkpoint protocol][protocol].

Suggested wording:

> HMAC authenticates immutable operation content internally. The demo also produces Ed25519-signed Merkle checkpoints and inclusion proofs for audit history. Public verification requires a trusted expected public key and retained checkpoint evidence; local filesystem anchors are not an independent witness or timestamp authority.

Do not claim an implemented compact consistency-proof API, incremental tree, or full Certificate Transparency implementation. Prefix/tree checking currently uses full recomputation.

### R05. Pages 7–8: the key and demo-readiness descriptions are outdated

**Priority: high.**

Already implemented:

- separate issue / verify / admin / checkpoint-signer permissions;
- issuance and verification through APIs without returning key bytes;
- software key storage with operating-system restrictions;
- HMAC rotation, new issuance with the active key, and historical verification using retained old keys.

Not yet implemented:

- a real KMS/HSM adapter;
- independent operating-system/cloud service accounts and IAM;
- hardware-enforced non-exportability of keys;
- independently owned deployment/backup/retention.

All local JVMs run under a shared Windows account. API separation exists; isolation from the common host administrator does not. "Only the service has access" is therefore acceptable as an API description or production requirement, not an absolute guarantee about the local machine.

Old keys are used for historical verification under API rules. This is not a hardware-enforced prohibition against someone possessing the software secret computing a new MAC with an old key.

Support: [LocalKeyVault.java][vault], [ServiceAuthenticationFilter.java][auth], [IssuanceService.java][issuance], [status][status].

### R06. Page 7: a verification-only API is not possession of the HMAC key

**Priority: high for explanatory accuracy.**

The statement that anyone who can verify a MAC can create one is true for the holder of the symmetric key. It is false for an API client permitted only to invoke verification.

In our demo, the processor does not receive the key and cannot issue MACs. API-permission tests check this.

Suggested wording:

> Anyone who possesses the HMAC key can generate valid MACs. A client restricted to a verification-only API need not possess that key and can be denied MAC-generation authority, as in this implementation.

This separation does not turn HMAC into a public signature or remove trust in the key service itself.

### R07. Pages 3–4: canonicalization must match the existing specification

**Priority: medium; required correction.**

The actual field format:

`field ID: uint8 | type: uint8 | byte length: uint32 big-endian | value`

It is not the literal field name plus length. Fields appear in a fixed order:

`domain, schemaVersion, keyId, id, ledgerId, type, fromAccountId, toAccountId, amountMinor, currency, createdAtMicros, relatedOperationId, idempotencyKey`.

Time: strictly int64 UTC Unix epoch **microseconds**, not "milliseconds or microseconds."
UUID: 16 bytes. Integers: int32/int64 big-endian. Null: a separate type tag with zero length. Strings: UTF-8; permitted identifiers are restricted to ASCII, and Unicode is not silently normalized. A missing required relation is not conflated with explicit null.

Instead of a large byte-layout table, the article can provide a compact, precise paragraph and a link to the published protocol and golden test vector.

Support: [CanonicalEncoder.java][encoder], [PROTOCOL.md][protocol].

Only schema v1 is currently supported; other versions are rejected. Including schemaVersion allows protocol evolution, but does not mean future-version decoders already exist.

### R08. Pages 4 and 8: a MAC does not attest to real time or human identity

**Priority: high for correctness of the guarantee.**

The MAC protects the recorded createdAtMicros value from subsequent alteration. It does not independently prove that the business event actually happened at that time. The host application uses the local clock; there is no separate TSA.

Similarly, "attributable" must not imply established identity of a particular person or non-repudiation: the module has no mandatory actorId or personal signatures.

Suggested wording:

> The MAC binds the recorded values, including the timestamp, and detects their subsequent alteration. It does not independently establish the real-world event time, the identity of a human actor, or that actor's business entitlement.

Business authorization remains the integrating application's responsibility. Aligning the article does not require adding a separate Identity Provider or expanding the agreed scope.

### R09. Pages 2 and 4–5: append-only behavior and corrections need a defined scope

**Priority: medium.**

Do not claim that nothing anywhere in the system is updated. Operation facts and their associated log are immutable; balances, status projections, leases, and retry state have legitimate updates. With full primary control, an attacker can alter even protected facts: the system detects the discrepancy and does not use the forgery as financial authorization.

Correction of a completed operation uses a separately executed exact REVERSAL followed by a separately authenticated CORRECTION. This is not one atomic correction transaction. Compensation can be rejected, for example if funds for the reverse debit are unavailable or an account is held.

Atomic cancellation/supersession of an unfinished operation is not yet available. Both related operations include the original-operation reference in their MAC.

Support: [Processor.java: correction rules][corrections].

### R10. Pages 2 and 6–7: asynchrony does not replace verification before execution

**Priority: high for completeness of the description.**

The article can retain background reconciliation, but must not imply that all checks occur after the financial action and independently of it.

- MAC issuance synchronously depends on writing the issuance receipt.
- Execution requires completed checks of trusted dependencies.
- A temporary outage means retry/queueing, not permission for an unverified payment.
- A confirmed invalid operation enters quarantine.
- The primary outbox is a hint, not authorization.
- Completed settlement and publication of its audit outcome are separated by a durable outbox; temporary absence of a projection does not permit another debit.
- Do not promise that stopping the key service immediately cancels an already verified in-flight execution.

Add a short account of fail-closed behavior and recovery. Do not introduce automatic recipient blocking as an implemented feature: quarantine currently applies to the operation, while account holds are managed separately.

## 3. Missing evidence and positioning

### R11. Pages 1 and 9: the lower-cost claim is unmeasured

The phrase "fraction of the operational cost" is not supported by a comparison, baseline, or cost model. A load-test PASS does not establish lower cost than a blockchain or ledger database.

Replace it with a bounded statement: this threat model does not require distributed consensus, but comparative cost and performance were not assessed here.

### R12. The article needs the demo's actual results

This is an important engineering contribution that the article currently hides.

| Measurement | Confirmed result |
|---|---|
| Java / Maven verify | 75 tests, 10 suites, 0 failures/errors/skips |
| Functional and attack scenarios on PostgreSQL | 15 of 15 passed |
| Offered load | 20 transfers/s, 120 seconds, 8 test accounts |
| Completion | 2,400 unique operations completed, 0 errors |
| Steady completion, seconds 10–120 | 20.0091 TPS |
| Whole run with warmup and drain | 19.9181 TPS |
| p95 end-to-end | 708.996 ms |
| Real JVM stops | Processor and key-service recovery completed without a second financial effect |

Completion in this test includes the protected operation outcome, correct postings, and delivery of the durable audit outcome—not just a successful HTTP response.

Sources: [publishable acceptance extract][verification], [recovery report][recovery]. Machine conditions and precise run descriptions: [status][status].

State the limitations explicitly: one Windows host, software keys, simulated money; not maximum throughput, a ten-minute soak, a million records, or a production SLA. Database failures, power loss, and PITR were not tested by this run.

### R13. No comparison with close alternatives or clear engineering contribution

The master plan requires comparisons not only with blockchain, but also with ledger databases, verifiable logs, and ordinary auditing. The article lacks them.

Explain briefly that the contribution is not a new HMAC, but a reproducible protocol and integration of verification with protected execution and independent evidence.

Be careful with "without migration": the current demonstrator shows a specific PostgreSQL path and a separate settlement domain. It is not a ready-made transparent adapter for every existing database or a tested MySQL integration.

Verified external reference points:

- Ledger is supported in SQL Server 2022+ and Azure SQL; do not repeat the earlier conversation's claim that this approach requires Azure exclusively. [Microsoft Ledger overview](https://learn.microsoft.com/en-us/sql/relational-databases/security/ledger/ledger-overview?view=sql-server-ver17).
- RFC 9162 supersedes RFC 6962 and provides useful Merkle proof/checkpoint constructions. Do not call our demo a Certificate Transparency implementation or claim that a tree eliminates all ordering problems. [RFC 9162](https://www.rfc-editor.org/rfc/rfc9162.html).
- AWS announced the end of QLDB support on July 31, 2025. This does not establish a lack of competitors or proven market uniqueness: the same AWS publication discusses ScalarDL as an alternative. [Official AWS article](https://aws.amazon.com/jp/blogs/news/migration-from-amazon-qldb/).
- When opened directly during this review, the old QLDB URL from the master plan returned 404; recheck a working link for the published article.

Positioning for this version: a reference implementation of verifiable integrity and protected execution over ordinary PostgreSQL, with explicitly identified trusted components. Broader portability remains a goal.

### R14. Pages 1, 3, and 9: editorial and publication accuracy

- "No string format is safe" is too categorical: a string format can also be rigorously canonicalized. Our protocol selects an integer timestamp.
- The problem with simple concatenation is missing unambiguous boundaries. Fixed width can also define boundaries; not every concatenation of bytes is incorrect.
- RBAC/database policies are security mechanisms, but are insufficient against an administrator who can bypass them. Replace "not a security mechanism" with "not sufficient for this threat model."
- Replace "permanent" with retained immutable history within a defined trust/retention model: cryptography does not make bytes indestructible.
- The literal placeholder `[repository URL]` remains at the end. Before publication, a working link to a specific release/tag/commit containing the code, protocol, and evidence is needed.
- Add a short References / Further reading section with direct links. NIST document numbers alone are insufficient for a verifiable technical article.
- Format, length limits, prior-publication rules, and anonymous-review requirements have not been checked for a specific conference because no event has been identified. That is a separate check after choosing the venue.

All nine pages are visually readable; no obvious clipping, overlap, or corrupted characters were found. The primary risk is substance rather than layout. This review used the PDF skill for complete extraction and visual inspection; the PDF was not edited.

## 4. Short replacement for the Reference implementation section

The following is proposed text, not an already applied edit:

> The current local reference implementation runs three Java services and three PostgreSQL instances. It combines byte-exact, versioned canonicalization and HMAC-SHA256 with independent issuance receipts, inventory reconciliation, role-separated service APIs, HMAC key rotation, and atomic, idempotent settlement in a protected database. Audit events are committed through a Merkle tree with Ed25519-signed checkpoints and inclusion proofs.
>
> In a recorded 120-second local experiment, the system accepted 20 transfers per second and completed all 2,400 unique operations without errors. Completion throughput was 20.009 TPS in the post-warmup measurement window and 19.918 TPS over the whole run including drain; p95 end-to-end latency was 709 ms. These measurements concern this software-key simulation and workload, not production capacity or comparative cost.
>
> Production hardening remains necessary: real KMS/HSM integration, independent operational ownership and IAM, encrypted service transport, independently retained checkpoints and trusted key distribution, backup and recovery exercises, scalable history verification, dependency maintenance, and external security review. Local filesystem anchors are not an independent timestamp authority or WORM storage.

## 5. Acceptance criteria for the revised article

Every item below concerns the future revised edition and is therefore not yet marked complete.

- [ ] PUB-01: explicitly limit the threat model to primary-database compromise; identify the trusted app/key/audit/processor/settlement/host.
- [ ] PUB-02: no authorization based solely on the existence of VERIFIED; describe the receipt, exact content, recheck, and same snapshot.
- [ ] PUB-03: show the protected settlement database; atomicity is local and audit relay is eventual.
- [ ] PUB-04: distinguish deletion/inventory from Merkle audit; do not present a MAC chain as implemented or a mandatory next step.
- [ ] PUB-05: distinguish operation HMAC from Ed25519 checkpoints; do not call state events HMAC-signed.
- [ ] PUB-06: match canonicalization to v1, specify microseconds and link vectors; retain keyId and all additional fields.
- [ ] PUB-07: distinguish verification-only API authority from key possession; correctly mark rotation as implemented.
- [ ] PUB-08: distinguish local software keys/shared host from production KMS/HSM/IAM.
- [ ] PUB-09: no promises of true time, author identity, absolute immutability, or automatic compliance.
- [ ] PUB-10: describe corrections as a compensating workflow for completed operations, not atomic rollback.
- [ ] PUB-11: describe retries/fail-closed/quarantine and protected funding without a bypass through initial balances.
- [ ] PUB-12: include actual results with measurement windows and limitations; remove unsupported cost advantages.
- [ ] PUB-13: include related work and the engineering contribution without claiming a unique primitive or universal ready-made adapter.
- [ ] PUB-14: demo/production diagrams and captions distinguish existing code, confirmed tests, and future infrastructure.
- [ ] PUB-15: include a verified public link to a repository version and References; check the chosen conference's rules separately.
- [ ] PUB-16: cross-check the revised edition against code and evidence again after editing.

These corrections do not require redesigning the cryptography to fit the old text: the article needs to match the current implementation. Production stages remain separate tasks, not a condition for an honest description of the local demo.

[status]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/docs/reference/implementation-status.md
[arch]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/docs/reference/architecture-decisions.md
[protocol]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/docs/reference/protocol.md
[plan]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/docs/reference/master-plan.md
[encoder]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/tokenization-module/src/main/java/com/demo/integrity/CanonicalEncoder.java:47
[issuance]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/tokenization-module/src/main/java/com/demo/keyservice/IssuanceService.java:23
[processor-check]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/transaction-security-module/src/main/java/com/demo/securityapp/Processor.java:99
[processor-settle]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/transaction-security-module/src/main/java/com/demo/securityapp/Processor.java:132
[processor-inventory]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/transaction-security-module/src/main/java/com/demo/securityapp/Processor.java:52
[corrections]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/transaction-security-module/src/main/java/com/demo/securityapp/Processor.java:172
[audit]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/transaction-security-module/src/main/java/com/demo/securityapp/AuditLog.java:25
[vault]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/tokenization-module/src/main/java/com/demo/keyservice/LocalKeyVault.java:47
[auth]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/tokenization-module/src/main/java/com/demo/keyservice/ServiceAuthenticationFilter.java:38
[verification]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/docs/evidence/local-verification-2026-09-05.json
[recovery]: C:/Users/vikto/Documents/Codex/2026-05-23/amount-amount-created-pending-processed-complete/docs/evidence/local-recovery-2026-09-05.json

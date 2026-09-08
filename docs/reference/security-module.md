# Independent integrity processor

This executable service runs on port 8082. The trusted financial state lives in Audit/Protected PostgreSQL (the same physical database as evidence, with a separate accounting role); the source transaction database is treated as attacker-controlled input. The key service on port 8081 supplies immutable issuance receipts and verifies HMACs. No HMAC key is loaded into this process.

## Durable processing

Source outbox rows are delivery hints. The dispatcher sends a persistent UUID-only message to embedded ActiveMQ and acknowledges the source hint only after durable broker acceptance. The consumer commits the protected `operation_jobs` insert before committing the transacted JMS receive. A crash in either acknowledgment gap can repeat delivery; the SQL operation ID prevents duplicate financial effects. Claims use compare-and-set updates and expiring leases. Retries retain the same operation ID. Independent paginated issuance and source scans find missing source records and missing outbox events, including records already settled.

The worker rejects malformed JSON, duplicate JSON names, unsupported fields, primary UUID/timestamp mismatch, canonical hash mismatch, differing issuance receipts and invalid MACs. Dependencies being unavailable causes a retry; it does not authorize an operation or accuse an account owner. No automatic account hold follows an untrusted recipient field.

After verification and durable audit recording, the source is read again. Settlement uses exactly that checked immutable snapshot. A subsequent source change cannot change the amounts or recipients being settled. Reconciliation reports post-settlement source tampering without rewriting the trusted completed result.

A single settlement transaction locks the operation inbox and accounts in UUID order, checks account holds and funds, writes the balanced journal, updates balances and records the outcome/outbox. Replay cannot add a second journal for an operation. An injected failure after debit rolls back all financial effects. Only the funding treasury may have a negative balance, and `TRANSFER` cannot spend treasury funds. The local ledger supports USD only.

Reversals must exactly reverse the completed predecessor and may occur only once. A correction follows a completed reversal, references its original operation, and may occur only once for that predecessor. These restrictions also prevent reference cycles. Unknown or held accounts, arithmetic overflow and insufficient funds cause a protected `REJECTED` result with no postings.

## Audit and checkpoints

`audit_events` is append-only to the runtime role. Sequence allocation locks a singleton head; event IDs deduplicate retries. The audit byte format includes a domain identifier, sequence, microsecond timestamp, event UUID, operation UUID, event type and detail. Strings have 32-bit big-endian byte lengths. Merkle hashing uses `SHA256(0x00 || event)` and `SHA256(0x01 || left || right)` with the RFC 9162 split rule.

Checkpoints are signed by the separate key service and anchored in its filesystem, outside both PostgreSQL databases. Before extending the log the processor verifies event bytes, contiguous sequence, head/count agreement and the entire prefix represented by the external checkpoint. Truncating the log and rewriting its local head cannot bypass the external anchor. Signature verification pins the public key from the separately configured key service; production should configure `processor.checkpoint-public-key` from a trusted deployment channel.

`GET /internal/audit/proof/{index}` exports the leaf hash, bottom-up proof and signed checkpoint. External verifiers must pin the signing public key separately and validate the claimed index and tree size as well as the proof hashes. `MerkleTree.verify(leaf,index,treeSize,proof,root)` implements the structural check.

Current checkpoints rebuild the tree in memory every two seconds. That implementation is suitable for this bounded demo and the measured dataset, but it is not an incremental million-record proof index. Reconciliation is periodic, not instantaneous. Missing-record eligibility uses a five-second operation-age threshold from authenticated `createdAtMicros`, assigned before the issuance request; it is not a guaranteed publication grace after receipt commit or a detection SLA. Further delay depends on inventory scanning. An issued-but-missing record becomes an incident and quarantined operation, without proving malicious intent. All local components ultimately share the machine administrator's trust boundary. Real off-host anchoring, HSM/KMS, network TLS and independent infrastructure ownership remain deployment work.

An integrity incident also creates its first per-operation notification in the same protected audit transaction. A separate dispatcher retries minimal email delivery; SMTP availability is not an execution permission. Local startup uses Mailpit capture, not external inbox delivery. See [notification design and limits](notifications.md).

## APIs and credentials

- `GET /health`: public readiness summary; processing waits until audit validation succeeds.
- `POST /internal/accounts` with `{id,name}`: register a zero-balance account.
- `GET /internal/accounts/{id}`: protected balance and hold state.
- `GET /internal/operations/{id}`: trusted outcome, checked operation, postings and `outcomeRelayed`.
- `GET /internal/audit?operationId=...`: independent audit events.
- `POST /internal/accounts/{id}/hold` with `{held:true|false}`: operator only.

Ordinary internal endpoints require `PROCESSOR_APP_TOKEN` or `PROCESSOR_ADMIN_TOKEN`; account holds and test endpoints require the distinct admin token. Both must contain at least 24 characters. Source database credentials do not grant these API rights.

Account registration and hold/release transitions atomically record a protected `account_audit_outbox` event with their state change. Audit outages cannot silently discard these events; the relay appends them after recovery. Registration creates no spendable money.

`outcomeRelayed=true` means the protected outcome has been appended to audit and projected to the source status table. Projection is eventually consistent; settlement and audit databases do not share one ACID transaction. Trusted reads come from settlement, never from source status rows.

Fault hooks are disabled by default. With `PROCESSOR_TEST_FAULTS_ENABLED=true`, the admin can configure a one-use pause after verification or failure after debit through `POST /internal/test/faults` with `{operationId,pauseAfterVerifyMillis,failAfterDebit}`. The pause is bounded to 15 seconds. `GET /internal/test/faults/{id}` reports `paused`; `POST /internal/test/checkpoint` forces a checkpoint check. These endpoints are solely for the explicit local test profile.

Schemas are applied by the bootstrap administrator, not by runtime application startup. The runtime roles have no DDL, DELETE or journal/event UPDATE rights. See `transaction-security-module/src/main/resources/audit-schema.sql` and `transaction-security-module/src/main/resources/settlement-schema.sql` and the root bootstrap scripts for actual grants.

## Verification

Run the reactor's `mvn -pl transaction-security-module -am test`. Tests use separate H2 databases in PostgreSQL compatibility mode to exercise atomic rollback, concurrent overspending, replay, key/audit outage, malformed input, TOCTOU, holds, durable retries, reversal/correction, issuance deletion and external-anchor tampering. Real PostgreSQL grants and the full 20-TPS path require the root local integration and load runner; H2 compatibility mode is not proof of PostgreSQL role enforcement.

## Embedded delivery adapter

`delivery/OperationDelivery` defines transport of discovery hints. `EmbeddedActiveMqDelivery` owns ActiveMQ Classic 6.3.2, VM-only transport with `create=false`, KahaDB disk-sync `always`, bounded memory/store usage, persistent messages without TTL, and unlimited delayed retry for valid hints whose SQL handoff fails. Malformed envelopes go to a persistent invalid-hint queue without deserializing objects. Separate sessions and locks keep producer backpressure from blocking consumer progress.

This is at-least-once delivery, not XA or cross-system exactly-once. The broker shares JVM/host failure with the processor. Its local journal supports restart recovery, but does not provide independent failover or prove greater throughput. Kafka or a remote broker would need a new adapter and explicitly reviewed acknowledgment/offset and partition semantics.

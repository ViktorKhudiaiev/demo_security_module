# Local integrity protocol v1

This module produces an ordinary shared-library JAR and a separate `-exec.jar` key-service application. The application scans `com.demo.keyservice` only. Its HMAC and Ed25519 private material never appears in an API response. It uses local OS-protected files, not a hardware security module. Host administrators remain trusted.

## Operation commitment

`CanonicalEncoder.encode(operation, keyId)` emits the following fields in exactly this order. Each field is a TLV: one-byte field ID, one-byte type, four-byte unsigned big-endian length in bytes, followed by that many bytes. Null uses type 0 and length 0. UTF-8 string uses type 1; UUID uses type 2 and 16 bytes (most-significant 64 bits followed by least-significant 64 bits, both big-endian); int32 uses type 3 and 4 bytes; int64 uses type 4 and 8 bytes. Integers are signed two's-complement big-endian with domain validation described below.

| ID | Field | Type |
|---|---|---|
| 1 | Literal `secure-transfer/operation/hmac/v1` | UTF-8 |
| 2 | schemaVersion, exactly 1 | int32 |
| 3 | exact immutable keyId | UTF-8 |
| 4 | id | UUID |
| 5 | ledgerId | UUID |
| 6 | type | UTF-8 |
| 7 | fromAccountId | UUID |
| 8 | toAccountId | UUID |
| 9 | amountMinor | int64 |
| 10 | currency | UTF-8 |
| 11 | createdAtMicros | int64 |
| 12 | relatedOperationId | null or UUID |
| 13 | idempotencyKey | UTF-8 |

All wire fields are required; relation may explicitly be JSON null. An omitted relation is rejected rather than conflated with explicit null. The account IDs must differ. Amount is 1 through 1,000,000,000,000 inclusive in minor currency units; time is a positive exact UTC Unix epoch microsecond count. Currency is exactly three uppercase ASCII letters. Operation type is `TRANSFER`, `FUNDING`, `REVERSAL`, or `CORRECTION`. Reversal/correction require a non-self relation; transfer/funding forbid a relation. Funding originates from treasury UUID `00000000-0000-0000-0000-000000000001`. Treasury authorization and ledger invariants are independently enforced by settlement.

`keyId` and `idempotencyKey` match `[A-Za-z0-9._:-]{1,128}`. This v1 protocol has no free-form Unicode text fields: unsupported strings are rejected, never normalized or silently truncated. Any future rules require a new supported schema version and retained historical decoder.

`contentHash` is lowercase hexadecimal SHA-256 of these exact bytes. `mac` is lowercase hexadecimal HMAC-SHA256 of the same bytes with the named 256-bit key. The hash is not a substitute for the MAC. Verification uses a constant-time MAC comparison and requires an identical independent issuance receipt. User authorization remains an upstream application responsibility; service authentication controls who may request a MAC.

The golden-vector fixture in `CanonicalEncoderTest` has independently calculated content hash `1813bad87578b52caa23303f23369c469f5a4ccca2991ad9fefc4b93871c7bc4`. With the test-only 32-byte key consisting of repeated `0b`, the MAC is `d8ea729dbe474b62efa1c7a2b72a62da48b480c5c277d1d121d05e89c2f88958`. Production/local runtime keys are generated randomly; the fixture key is not used by the application.

## Issuance and retries

The independent receipt commits before the HTTP response containing a MAC is returned. A unique `(ledger_id, idempotency_key)` binds retries to the same business operation. Its business fingerprint includes domain, schema, ledger, type, accounts, amount, currency, relation and idempotency key; it excludes only the server-generated UUID and creation time. A matching retry returns the original signed operation. Changed business data or conflicting UUID returns HTTP 409.

`GET /v1/issuances/inventory?afterSequence=0&limit=1000` is verifier-only and returns `{items:[{sequence,signedOperation}],nextSequence}`. The sequence is an issuance inventory cursor; it is not itself a cryptographic proof of completeness and may have database allocation gaps. This inventory permits detection of operations deleted before the primary outbox was ever observed. Receipt payloads are immutable under runtime database permissions.

## Credentials and endpoints

Four distinct nonempty bearer credentials are required: writer, verifier, key administrator and checkpoint signer. Writer alone may call `POST /v1/mac/issue`; verifier alone may call `POST /v1/mac/verify` and issuance inventory. Writer and verifier can retrieve individual receipts. Administrator alone can rotate HMAC keys with `POST /v1/keys/rotate`; old keys remain available for verification. Signer alone can call `POST /v1/checkpoints`. The public health and public checkpoint endpoints expose no private material. Local HTTP binds to loopback; remote production deployments require authenticated encrypted transport and independent IAM.

## Signed checkpoints

`Checkpoint(logId,treeSize,rootHash,createdAtMicros)` commits to a Merkle root supplied by the authorized audit service. The key service does not reconstruct the tree and does not independently prove consistency of an increasing-size root; this is a trust assumption of the authenticated signer path. It refuses a smaller tree size, an older timestamp, or a different root at the same size. A same-size/same-root heartbeat with a newer timestamp is allowed.

Ed25519 signs the same TLV encoding, with fields: (1) `secure-transfer/checkpoint/ed25519/v1`, (2) int32 version 1, (3) exact signing keyId, (4) logId, (5) int64 treeSize, (6) lowercase 64-character root hash, (7) int64 createdAtMicros. `SignedCheckpoint` adds keyId, base64 signature, and X.509 SubjectPublicKeyInfo base64 publicKey. A verifier must obtain and pin the expected public key through a trusted channel; it must not trust a replacement key merely because it accompanies a signature.

Every signed checkpoint is written to an individual file in `KEY_DIRECTORY/anchors`, outside the audit and primary databases, before its response returns. The latest pointer can be reconstructed from these files after a crash. These files are local external anchors relative to a database compromise; they are not WORM and do not survive compromise/deletion of the whole host. This module does not provide an independent timestamp authority, public witness, or multi-party transparency log. In production, publish/preserve checkpoints and the pinned public key in independently controlled storage.

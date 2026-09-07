# Technical Reference for Secure Transaction Integrity

Date: 2026-09-05  
Audience: project owner, technical reviewer, article or presentation author  
Purpose: explain the terms plainly, without dangerous oversimplifications

The full backlog and acceptance criteria are in [master-plan.md](master-plan.md).

> Historical context: this reference preserves the original planning-stage explanations and implementation observations. Statements about what the project "currently" does, missing features, and unmeasured targets describe that earlier baseline, not today's implementation inventory. For current implementation and recorded results, use [implementation-status.md](implementation-status.md) and the [current protocol](protocol.md).

> This file explains technologies and regulatory references. It is not legal advice and does not establish the project's compliance with any regulation.

## 1. The complete picture in five minutes

Imagine a bank instruction: "transfer 25 dollars from Alice to Bob."

1. **Canonicalization** converts the instruction's meaning into one strictly defined byte sequence.
2. **HMAC** uses a secret key to create an authentication tag for those bytes.
3. If the amount or recipient changes, the original tag no longer matches.
4. An **audit store** in another trust domain records exactly which bytes were verified.
5. The **processor** executes only that verified snapshot, and only once.
6. A **Merkle tree** combines many audit records into one root hash.
7. A **digital signature** over the root allows checkpoint verification without knowing the secret HMAC key.
8. An external checkpoint copy helps detect deletion or rewriting of the log.

What this provides:

- detection of changes to protected fields;
- inability to create an acceptable tag without the key or an authorized KMS call;
- verifiable history;
- safe handling of repeated events;
- evidence that a record was included in a particular state of the log.

What it does not provide automatically:

- encryption and confidentiality;
- protection if the attacker obtains the key and every administrative domain;
- availability after the entire database is deleted;
- legal non-repudiation through an algorithm alone;
- SEC/FDA compliance without the other system and organizational controls.

## 2. Hashes, MACs, HMACs, and digital signatures

### 2.1. Hash

A cryptographic hash function receives arbitrary bytes and returns a short, fixed-length value.

In simplified form:

    message bytes → SHA-256 → 32-byte digest

Useful properties:

- identical bytes produce the same digest;
- a small change usually changes the digest completely;
- recovering the original message from its digest is practically infeasible;
- finding two different messages with the same digest is difficult.

A hash does not use a secret. An attacker who changes a record can therefore calculate a new ordinary hash independently. A hash alone detects accidental corruption, but does not protect against an active attacker who can replace both the data and its digest.

### 2.2. SHA-256

SHA-256 is a widely used cryptographic hash function with a 256-bit, or 32-byte, result.

The hexadecimal representation of one digest occupies 64 characters, because each byte is represented by two hexadecimal characters.

SHA-256 is not:

- encryption;
- a signature;
- HMAC without the additional construction and key.

### 2.3. MAC

**MAC** means Message Authentication Code.

A MAC uses a secret key and a message. It authenticates two things:

- the bytes have not changed since the MAC was created;
- the MAC was created by someone with the required secret key or permission to invoke the protected MAC operation.

A MAC does not hide the message's contents.

### 2.4. HMAC

**HMAC** means Hash-based Message Authentication Code. It is one abbreviation, not a separate "HM" and "AC."

HMAC is a standard way to construct a MAC from a cryptographic hash function and a secret key. The project uses **HMAC-SHA256**: HMAC with SHA-256.

Formally, HMAC is more complex than hash(key + message). Do not replace the standard HMAC construction with custom concatenation.

Official description: [RFC 2104](https://www.rfc-editor.org/rfc/rfc2104).

SHA-2 definition: [NIST FIPS 180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final).

A simple analogy: the record sits in a transparent envelope, so it can be read; HMAC is a seal that only the holder of a secret tool can apply correctly.

### 2.5. HMAC tag / token

More precise names for an HMAC result are:

- MAC;
- HMAC tag;
- authentication tag;
- integrity tag.

The word token is acceptable as an internal name, but can be confusing. The implemented module does not perform tokenization in the usual sense.

### 2.6. Tokenization

Tokenization usually means replacing a sensitive value with a safe surrogate token.

Example:

    card number 4111...1111 → token tok_7FH2...

The system separately stores or can resolve the token ↔ original relationship. This reduces the spread of sensitive data.

The current project does not replace or hide anything. It computes HMAC. The name **tokenization-module** should therefore be changed.

### 2.7. Encryption

Encryption makes data unreadable without a key. HMAC and hashes do not encrypt data.

It is possible to use both:

- encryption for confidentiality;
- HMAC/signatures for integrity and authenticity.

### 2.8. Digital signature

A digital signature has a key pair:

- the private key signs;
- the public key verifies.

The verifier does not need the private key, so an external auditor can verify signatures without acquiring the ability to create new ones.

This is the main difference from HMAC: anyone independently verifying HMAC with the raw secret can technically generate another HMAC.

### 2.9. Ed25519

Ed25519 is a specific modern digital signature scheme in the EdDSA family. Its specification and test vectors are in [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032).

The project proposes Ed25519 for periodic Merkle checkpoint signatures, not necessarily for each transaction row.

Important: the selected cloud KMS/HSM must actually support the selected algorithm. Do not promise Ed25519 for every provider without checking its current API.

### 2.10. Non-repudiation

In everyday terms, this is the ability to prevent a signing party from plausibly denying its signature.

A digital signature provides an important technical foundation for independent verification, but does not resolve the entire legal question on its own. It requires:

- established identity of the private-key owner;
- strict key custody;
- protection against shared key use;
- signing time;
- policies and audit logs;
- applicable law and procedures.

The precise phrase is therefore "publicly verifiable digital signature"; legal non-repudiation should be assessed separately.

### 2.11. Constant-time comparison

Ordinary comparison may stop at the first mismatching byte. Response time can then depend slightly on the number of matching initial bytes.

Constant-time comparison compares the entire tag in a way designed to reduce timing-side-channel leakage. This is a standard protective measure for MAC/signature values. It does not fix a weak key or incorrect canonicalization.

## 3. Canonical representation

### 3.1. What canonicalization means

One logical record can be serialized into different bytes:

- JSON fields can appear in a different order;
- time can be written with Z or +00:00;
- 25 can be represented as 25, 25.0, or 025;
- a Unicode character can have multiple code-point sequences;
- NULL, an empty string, and an omitted field can accidentally be conflated.

HMAC operates on bytes, not "meaning." The producer and verifier must therefore obtain byte-for-byte identical input. Rules that produce a unique representation are called canonicalization.

### 3.2. What the project currently does

The current Java code forms a string of this shape:

    transactionId=<UUID>|fromAccountId=<UUID>|toAccountId=<UUID>|amountCents=<long>|createdAt=<Instant>

It then encodes the string as UTF-8 and processes it with HMAC-SHA256.

This is better than unnamed concatenation such as AB + C, because it has field names, delimiters, and fixed-format UUIDs. It is not yet an inter-service specification, however:

- no canonicalizationVersion;
- no keyId;
- the Instant format is not specified independently of Java's behavior;
- escaping is undefined if string fields are added;
- NULL and absent are not specified;
- no golden test vectors;
- no rule for preserving old versions.

### 3.3. Serialization collisions and hash collisions are different

With raw concatenation:

    ("AB", "C") → "ABC"
    ("A", "BC") → "ABC"

two records produce identical bytes before hashing. This is **not a cryptographic SHA-256 collision**, but ambiguous serialization.

Remedies:

- fixed-width fields;
- length prefixes;
- type/tag prefixes;
- an unambiguous serialization standard.

### 3.4. Length prefix

The value's length is stored before the value.

Conceptual example:

    length=2, value=AB
    length=1, value=C

Field boundaries can no longer be shifted unnoticed.

The specification must answer:

- how many bytes the length occupies;
- whether it is signed or unsigned;
- big-endian or little-endian;
- maximum length;
- how NULL is encoded;
- whether the tag/type is included in the protected bytes.

### 3.5. TLV

**TLV** means Type/Tag – Length – Value.

Each field contains:

- a tag: the field identifier;
- a length;
- the value bytes.

Our plan adds a separate state byte: `0=absent`, `1=NULL`, `2=value`. When `state=2,length=0`, the value is present but empty, so absent, NULL, and empty have different bytes.

### 3.6. Big-endian and little-endian

These describe byte order within a multibyte number.

For the number 0x0102:

- big-endian: 01 02;
- little-endian: 02 01.

Either is valid, but producer and verifier must choose the same one. Network protocols often use big-endian, also called network byte order.

### 3.7. UTF-8

UTF-8 converts Unicode text into bytes. Saying "we use UTF-8" is necessary, but insufficient:

- Unicode normalization must be defined;
- escaping must be defined;
- allowed characters must be defined;
- malformed-input handling must be defined;
- length must be in bytes, not Java chars.

### 3.8. Unicode NFC

Some visually identical characters can be written as different code-point sequences. NFC is one form of Unicode normalization.

Two approaches are acceptable:

- normalize selected text fields to NFC before signing;
- forbid transformation and require preservation of the original Unicode code points.

The important part is choosing one. For example, RFC 8785 requires preserving parsed Unicode strings "as is" and does not itself perform NFC. It is therefore incorrect to say "we strictly use RFC 8785" while silently adding NFC without defining an extension.

### 3.9. NULL, empty, and absent

These are three different states:

- NULL: the field exists in the schema, but has no value;
- empty: the value exists, but has length 0;
- absent: the field was not supplied or is unsupported by the version.

If their business meanings differ, their canonical bytes must differ too.

### 3.10. Timestamp, UTC, and epoch

UTC defines the time scale, not the byte format.

Further choices are required:

- string or integer;
- seconds, milliseconds, or microseconds;
- rounding rules;
- permitted range;
- treatment of leap seconds;
- alignment with database precision.

The project's recommended option is signed 64-bit Unix epoch microseconds. The database stores exactly that value, or a timestamp that can be converted to and from it without loss.

### 3.11. Unix epoch

A count of time units from 1970-01-01T00:00:00Z. Epoch milliseconds count thousandths of a second; epoch microseconds count millionths.

### 3.12. Schema/canonicalization version

The version is included in MAC input and tells the verifier which rules to use when reconstructing the bytes.

Without a version, changing the format breaks verification of the entire old history or forces the verifier to guess the algorithm.

### 3.13. Domain separation

The same key is sometimes used for several message types. To prevent a tag for one context from being mistakenly accepted in another, the input includes a fixed domain label.

Example:

    SECURE_TRANSFER_INTENT

Status events and Merkle checkpoints use other labels or, preferably, separate keys.

### 3.14. Golden test vector

A fixed example containing:

- input fields;
- expected canonical bytes in hex;
- a key or public test key;
- the expected HMAC/signature.

A Java, Go, or Python implementation that produces a different value is incompatible with the specification.

### 3.15. RFC 8785 / JCS

[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) describes JSON Canonicalization Scheme:

- deterministic serialization;
- object-property sorting;
- strict rules for JSON primitives;
- no unnecessary whitespace.

It is a useful established precedent, not a mandatory choice. Monetary int64 values require attention to JSON/IEEE-754 limitations; the RFC recommends representing large integers as strings under an agreed rule.

## 4. Identifiers, versions, and corrections

### 4.1. UUID

A UUID is a 128-bit identifier, usually written as:

    550e8400-e29b-41d4-a716-446655440000

UUIDs are useful for distributed ID creation without central auto-increment allocation. A UUID does not prove order or independently protect against forgery.

The modern UUID specification, including UUIDv7: [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562).

### 4.2. Sequence

A sequence is a monotonically increasing audit-entry number.

It helps:

- establish order;
- determine the expected tree size;
- find gaps;
- construct the log.

A gap does not always mean an attack: a database sequence may skip a value after rollback. A sequence is therefore a useful operational signal, but cryptographic evidence arises only together with a protected commitment/checkpoint.

### 4.3. Immutable

Immutable means that an existing fact is not changed.

It is important to distinguish:

- a documentation claim;
- a prohibition in ordinary application code;
- database permissions/triggers;
- cryptographically tamper-evident history.

The current project calls the ledger/status immutable, but the database does not yet enforce that property.

### 4.4. Append-only

An append-only log permits adding new records, but not rewriting old ones.

An append-only policy reduces risk, but does not fully protect against someone with DDL/administrative access. Tamper evidence requires external commitments or a separate trust domain.

### 4.5. Correction

A correction is a new record that corrects the meaning of the original without rewriting it.

For an unexecuted intent, it is possible to:

- add a CANCELLED/SUPERSEDED event;
- create a new corrected intent with a correctsTransactionId reference.

### 4.6. Reversal / compensating transaction

Once money has been posted, history cannot simply be edited. A compensating operation is created with reverse ledger entries and a reversesTransactionId reference.

A new, correct transaction is then created if necessary.

The reference must be included in the MAC; otherwise, an attacker could redirect the reversal/correction.

### 4.7. Version

The project uses several kinds of version:

- canonicalizationVersion: byte format;
- keyVersion/keyId: the key being used;
- policyVersion: business/security rules;
- transactionVersion: logical-entity version;
- event sequence: order of history events.

A single timestamp cannot substitute for all of them.

## 5. Deletion, hash chains, and Merkle trees

### 5.1. Why a per-row HMAC cannot detect deletion

If every remaining row has a valid MAC, deleting an entire row does not alter its neighbors' MACs. The same applies to truncating the end of a log.

Detection requires protected information about the complete set and order of records.

### 5.2. Hash/MAC chain

In a linear chain, record n includes the hash/MAC of record n−1:

    link(n) = H(record(n) || link(n−1))

Modification, deletion, or reordering in the middle breaks subsequent links relative to a retained final anchor.

Advantages:

- simple to explain and implement;
- suitable for a strictly sequential log.

Disadvantages:

- append depends on the previous head;
- verification of a long range is often linear;
- parallel ingestion and sharding require additional design.

### 5.3. Merkle tree

A Merkle tree has record hashes at its leaves, and each parent is a hash of two child nodes. One root hash commits to the entire ordered set of leaves.

Advantages:

- an inclusion proof usually contains O(log n) hashes;
- a consistency proof can show that the new log extends the old one;
- records can conveniently be batched and one root signed.

Limitations:

- leaves still need a defined order;
- the tree alone does not prevent an attacker from computing a new root;
- the root/checkpoint must be signed and retained outside the compromised domain;
- proof algorithms are more complex than a chain.

"Merkle is strictly better in every respect" is therefore too strong. Merkle is a better fit for our scalable audit log, but this is a design trade-off, not magic.

### 5.4. Leaf, node, and root

- leaf: the hash of one canonical audit entry;
- node: the hash of a pair of child hashes;
- root: the top hash of the entire tree.

Leaves and internal nodes must use different domain prefixes so that one cannot be interpreted as the other.

### 5.5. Inclusion proof

A short list of neighboring hashes that lets a verifier reconstruct the root from a particular leaf. A matching root proves that the leaf was included in a tree of the specified size.

It does not prove that the business record itself is truthful; only that its commitment is included in the signed log state.

### 5.6. Consistency proof

A proof that a tree of size N is an append-only continuation of a previously known tree of size M, where M is smaller than N, rather than a rewritten alternative history.

### 5.7. Checkpoint / Signed Tree Head

A minimal checkpoint contains:

- tree size;
- root hash;
- timestamp;
- algorithm and signing key ID;
- digital signature.

Certificate Transparency calls a similar structure a Signed Tree Head. In a custom project, signed checkpoint is a better term unless the full CT protocol is implemented.

### 5.8. External anchoring

A checkpoint copy is stored somewhere the database attacker cannot write:

- a separate cloud account;
- immutable object storage/WORM;
- an independent transparency log;
- a TSA;
- multiple observers.

Without an external anchor, the log administrator may try to build a new internally consistent history and replace local roots.

### 5.9. RFC 6962 and RFC 9162

[RFC 6962](https://www.rfc-editor.org/rfc/rfc6962) described Certificate Transparency v1 in 2013.

[RFC 9162](https://www.rfc-editor.org/rfc/rfc9162) describes v2 and **obsoletes RFC 6962**. An up-to-date article should primarily reference RFC 9162, mentioning RFC 6962 historically.

Both documents concern transparency logs for TLS certificates. We borrow Merkle inclusion/consistency concepts, but do not claim that the entire transfer protocol is Certificate Transparency-compatible.

### 5.10. Trillian

Trillian is open-source infrastructure for verifiable transparency logs using Merkle trees.

Its current official page marks the project as being in maintenance mode and recommends evaluating Tessera first for new log deployments. Trillian is therefore useful as a reference and established implementation, but should not automatically be selected for a new product without separate evaluation.

Official overview: [Trillian](https://google.github.io/trillian/).

### 5.11. immudb

immudb is a specialized tamper-evident/immutable database with cryptographic history verification. It is one product to include in a competitive comparison.

Our differentiation should not be phrased as "nobody else can do this." A possible distinction is an overlay for an existing relational application schema and controlled settlement without fully migrating data into a separate ledger database. Comparative implementation and benchmarks must still establish that distinction.

Official documentation: [immudb](https://docs.immudb.io/master/immudb.html).

### 5.12. O(n) and O(log n)

These are **asymptotic complexity** notations: a way to approximately describe how work grows as the number of records `n` increases:

- `O(n)`: work grows roughly linearly; in the worst case, verifying one million records requires traversing about one million elements;
- `O(log n)`: work grows much more slowly; a binary Merkle proof for one million leaves contains on the order of tens of hashes, not one million.

The notation does not specify exact milliseconds. A fast `O(n)` process on a small dataset may outperform a complicated `O(log n)` implementation, so real benchmarks are necessary.

### 5.13. Split-view attack

**Split view** means that the log operator presents different verifiers with two different histories, each internally cryptographically consistent. A root signature alone does not rule out this attack: a dishonest signer can sign two different roots of the same size.

Independent checkpoint publication, multiple witnesses, and exchange of observed checkpoints between verifiers help reduce the risk.

### 5.14. Gossip, witnesses, and checkpoint freshness

- **gossip**: exchanging checkpoints between independent observers to reveal incompatible states;
- **witness**: an independent party that checks and, where appropriate, co-attests a checkpoint;
- **checkpoint freshness**: how recently the latest trusted checkpoint was created and externally retained.

If a checkpoint is issued once a day, changes after the latest checkpoint may remain undetected until the next issuance. Security policy must therefore define:

- cadence: how often checkpoints are issued;
- maximum checkpoint age: the oldest acceptable checkpoint;
- maximum inclusion/merge delay: how quickly an accepted record must be included in a root;
- retention of the highest-seen tree size/root and rejection of regression to an older state;
- behavior when freshness expires and the source of trusted time.

A heartbeat checkpoint may be needed even when there are no new events. Otherwise, an observer cannot always distinguish normal silence from a frozen log.

### 5.15. Cryptographic commitment

A **commitment** is a short cryptographic value that binds the verifier to particular bytes or a set of records. A Merkle root commits to every ordered leaf of a tree of the specified size.

A commitment does not prove that business data is truthful. Its usefulness depends on unambiguous canonicalization, a trusted signature/anchor, and knowledge of which state counts as fresh.

### 5.16. Replay, rollback, freeze, and truncation

These are different attacks:

- **replay**: repeating a previously acceptable request or event; the MAC remains valid, so idempotency keys and a unique effect are required;
- **rollback**: replacing current state with an old, once-correct snapshot;
- **freeze**: continually presenting an old, validly signed checkpoint;
- **truncation**: deleting the end of a log.

A signature without a highest-seen/freshness policy does not necessarily reveal rollback or freeze. The verifier must remember or independently obtain a newer state.

### 5.17. Tamper-evident, tamper-resistant, and immutable

- a **tamper-resistant** control makes modification harder or forbids it;
- a **tamper-evident** control allows a completed modification to be detected relative to trusted evidence;
- **immutable** means unchangeable within an explicitly defined permission and retention model;
- **tamper-proof** is best avoided because it sounds like an absolute guarantee.

HMAC, chains, and Merkle checkpoints primarily provide tamper evidence. Database permissions and WORM add tamper resistance. None of these terms means that every system cannot be deleted under complete administrative control.

### 5.18. Sharding and global ordering

**Sharding** divides records among independent partitions for parallel processing. Ordering within each shard does not automatically create a single global order.

A shared checkpoint requires explicit rules: a central sequencer, a separate tree per shard with a higher-level root, or another deterministic combination method. A Merkle tree improves proofs, but does not independently eliminate the ordering problem.

## 6. Keys and trust boundaries

### 6.1. Secret key

The secret bytes used by HMAC. A weak passphrase such as change-me-in-real-environments is not a production key.

A randomly generated key of sufficient length and a lifecycle policy are required.

### 6.2. Key ID / key version

A non-secret key identifier. It is stored beside the MAC and tells the verifier which historical key to use.

The key ID does not allow the key to be calculated.

### 6.3. Key rotation

Transition from an old key to a new one:

1. create a new key ID;
2. sign new records with the new key;
3. retain old keys temporarily for verification only;
4. define retention/retirement;
5. log rotation events.

Simply deleting the old key is not possible if old HMACs must remain directly verifiable.

AWS KMS HMAC keys do not have automatic key rotation: a new key is created and the application switches to it. Historical records must therefore store the exact key ID, not merely an alias that can later be reassigned. See [AWS KMS HMAC keys](https://docs.aws.amazon.com/kms/latest/developerguide/hmac.html) and [AWS KMS rotation](https://docs.aws.amazon.com/kms/latest/developerguide/rotate-keys.html).

### 6.4. KMS

**KMS** means Key Management Service/System.

A managed KMS:

- creates and stores keys;
- performs cryptographic operations through an API;
- applies IAM policies;
- maintains an audit trail;
- usually does not return the raw key to the application.

For example, AWS KMS supports HMAC keys and separate GenerateMac/VerifyMac operations; keys are not exported in plaintext. See [AWS KMS HMAC keys](https://docs.aws.amazon.com/kms/latest/developerguide/hmac-create-key.html).

### 6.5. HSM

**HSM** means Hardware Security Module.

A specialized protected hardware/firmware environment for keys and cryptographic operations. A KMS is often built on HSMs, but the concepts are not completely interchangeable:

- HSM: the protected cryptographic module;
- KMS: the full lifecycle, permissions, and API management service.

### 6.6. Secret manager / vault

A secret store usually returns the secret to an application, after which it resides in process memory.

A KMS/HSM can perform a MAC/sign operation without revealing key material to the application. The latter is preferable for a strong boundary.

### 6.7. IAM, RBAC, and least privilege

- IAM: infrastructure identity and access management;
- RBAC: permissions assigned through roles;
- least privilege: each identity receives only the minimum necessary access.

Example:

- the Transfer API can create an intent and request GenerateMac;
- the verifier can read an intent and invoke VerifyMac;
- the processor can write settlement;
- the primary database user cannot write to audit/settlement;
- the primary DBA does not administer the KMS account.

### 6.8. Four-eyes principle

A critical action requires approval by two independent people/roles. This organizational control helps make trust separation real rather than merely diagrammed.

### 6.9. Trust domain / security boundary

A collection of systems and administrators whose compromise is considered together.

Two tables in one database under one sa are not two trust domains. Real separation requires distinct credentials, permissions, preferably a separate instance/account, and an independent administrative plane.

### 6.10. Signing oracle

A **signing oracle** is a service that an attacker can cause to calculate a valid MAC or signature over attacker-selected data. The key has not been stolen, but the attacker's result appears cryptographically correct.

Restricting raw-key disclosure is therefore insufficient. `GenerateMac` must check the caller's service identity, business purpose, tenant/ledger scope, allowed fields, and rate limits, and every call must be audited.

Within the project's agreed scope, this means service access: the application can create MACs, while the verifier can only verify. Whether a particular user may control an account remains a decision of the trusted host application. The module does not add its own Identity Provider or a mandatory actorId in transaction payloads.

### 6.11. TLS and mTLS

**TLS** encrypts the network connection and authenticates the server. **mTLS**, mutual TLS, additionally requires a client certificate, so both parties establish each other's identity.

mTLS is useful between the Transfer API, Integrity Service, and Processor, but does not replace application-level authorization. A valid certificate answers "who connected"; policy must still decide "may this caller invoke `GenerateMac` for this ledger and tenant?"

The modern base protocol TLS 1.3 is described in [RFC 8446](https://www.rfc-editor.org/rfc/rfc8446.html). Operating mTLS also requires a lifecycle for the CA and for issuance, rotation, and revocation of client certificates.

### 6.12. Actor, business authorization, and service identity

An **actor** initiates an action: a person or a program. **Business authorization** determines whether that actor may perform a particular action, such as transferring money from the selected account. **Service identity** identifies an application calling another service.

Our module's agreement is that the host application already checks users and business permissions. The module trusts that application, but not the primary database. It allows the application to call GenerateMac; the database administrator has no such service authority. A separate login screen, account-ownership checks, and actorId in every record are therefore not mandatory parts of the integrity module.

If an attacker obtains permission to call GenerateMac as the trusted application, the "database-only access" guarantee no longer applies. A cryptographically correct MAC does not independently prove that the application's decision was correct.

## 7. Reliable transaction processing

### 7.1. Database transaction and ACID

Transaction has two meanings here:

- business transaction: a money transfer;
- database transaction: an atomic group of SQL operations.

ACID:

- Atomicity: either everything applies or nothing does;
- Consistency: invariants are preserved;
- Isolation: concurrent operations do not observe dangerous intermediate states;
- Durability: a commit survives failure.

Debit, credit, idempotency marker, and terminal status must reside in one database transaction in the trusted settlement domain.

### 7.2. Ledger

Ledger also has two meanings:

- accounting ledger: debit and credit records from which a balance is derived;
- ledger database: a database with immutable/verifiable change history.

A ledger_entries table does not turn H2/PostgreSQL into a cryptographic ledger database.

### 7.3. Double-entry

For a transfer, one side decreases and the other increases by the same amount in the same currency. The postings must sum to zero.

Constraints or a transactional procedure are required, not merely the hope that Java will execute two INSERTs in sequence.

### 7.4. Event sourcing

Current state is derived from a sequence of immutable events rather than overwritten in one mutable field.

A history of status events resembles an event-sourced approach, but full event sourcing requires a defined model of versioning, ordering, replay, and invariants.

### 7.5. Outbox pattern

The business row and outbox event are written in one database transaction. A publisher then delivers the event to a broker/consumer.

This closes the gap where "data committed, but the message was not sent," but only with correct acknowledgement, retry, and idempotency handling.

### 7.6. Broker / queue

A broker stores and delivers messages between producers and consumers. Examples: RabbitMQ, ActiveMQ, Kafka.

LinkedBlockingQueue inside one JVM is a useful API-boundary simulation, but:

- it is lost on restart;
- it is not an external broker;
- it does not establish durable delivery.

### 7.7. At-most-once, at-least-once, and exactly-once

- at-most-once: a message can be lost, but is not repeated;
- at-least-once: a message should not be lost, but may arrive repeatedly;
- exactly-once: the business effect occurs exactly once.

In distributed systems, an exactly-once promise is usually achieved through at-least-once delivery plus an idempotent consumer, a unique key, and an atomic effect—not through "perfect delivery."

### 7.8. Idempotency

Repeating one operation produces the same outcome, not a second debit.

Practical mechanism:

- stable transactionId/idempotencyKey;
- a unique database constraint;
- atomic marker insertion plus postings;
- a duplicate is treated as an already processed success, not a new operation.

### 7.9. Deduplication

Detection of repeated messages by eventId/transactionId. Deduplication helps, but without atomicity a crash can occur between "checked" and "recorded."

### 7.10. CAS / compare-and-set

An update executes only if a record still has the expected version/state.

Example:

    UPDATE ... SET state='SETTLING', version=version+1
    WHERE id=? AND state='VERIFIED' AND version=?

If updated rows = 0, another worker has already claimed the record or its state has changed.

### 7.11. Locks and isolation

A row lock prevents two database transactions from changing the same record simultaneously. The isolation level determines visibility of concurrent changes.

Money requires explicit concurrency design: account reservation, serialized posting, a balance constraint, or another demonstrable mechanism.

### 7.12. TOCTOU

**Time Of Check To Time Of Use**: data is checked at T1 but used at T2, and changes between those moments.

An unsafe check:

    if a VERIFIED row exists → reread mutable transaction → execute

Safer:

- the VERIFIED record stores the exact contentHash/canonical snapshot;
- the processor compares the current hash again;
- or it directly executes the immutable verified snapshot;
- the claim and business effect are idempotent.

### 7.13. Fail closed / fail open

- fail closed: verification failure does not permit the action;
- fail open: the action continues without the control.

The recommended settlement default is fail closed with a durable PENDING queue. This reduces availability, so retries, a DLQ, monitoring, and understandable recovery are needed.

### 7.14. Retry, backoff, lease, and DLQ

- retry: another attempt after a temporary failure;
- exponential backoff: increasing intervals between attempts;
- lease: a worker's temporary right to process a record; another worker can claim an expired lease;
- DLQ: a dead-letter queue for messages that cannot be processed within the retry limit.

### 7.15. Reconciliation

Periodic independent searches for inconsistencies:

- intent without a verification record;
- verified intent without settlement;
- settlement without valid evidence;
- an unbalanced posting pair;
- audit sequence/checkpoint mismatch.

The reconciler matters because an attacker with DDL access to primary can disable triggers/outboxes.

### 7.16. Availability and DoS

**Availability** is the system's ability to accept and complete requests. **DoS**, denial of service, disrupts availability without necessarily forging data.

Fail-closed behavior protects against an unverified financial effect, but KMS/verifier failure increases backlog and latency. This trade-off is acceptable only with a durable queue, capacity limits, monitoring, and a predefined operational response. HMAC does not prevent `DROP DATABASE` or restore deleted data.

### 7.17. Latency, throughput, percentiles, and overhead

- **latency**: time to complete one operation;
- **throughput**: operations per unit of time, often requests/second;
- **p50/p95/p99**: values below which 50%, 95%, and 99% of observations completed, respectively;
- **tail latency**: the slow tail of the distribution, usually p95/p99;
- **overhead**: additional cost compared with the same workload without the control being studied;
- **baseline**: the control configuration against which overhead is calculated.

For example, p95 = 40 ms means that 95% of measured operations completed within 40 ms. A mean does not replace percentiles. The project should separately measure the write path, verification, checkpoint creation, full scans, and proof verification.

## 8. Databases, permissions, and recovery

### 8.1. DML, DDL, and DBA

- DML: SELECT, INSERT, UPDATE, DELETE on data;
- DDL: CREATE, ALTER, DROP of schema objects;
- DBA: a database administrator with broad privileges.

A security claim must specify the attacker's privilege level.

### 8.2. Trigger

Code automatically executed by the database on INSERT/UPDATE/DELETE. A trigger is useful as a preventive/detective control, but a DBA can usually disable or delete it.

Protection against a DBA cannot rely solely on a trigger in the same database.

### 8.3. PostgreSQL and MySQL

Widely used relational database management systems. The proposed layer's value is its potential integration with an existing system on such a database, but actual portability requires separate adapters and tests. "Works with any SQL database" is unacceptable without those tests.

### 8.4. H2

A lightweight Java database convenient for local tests. H2 Java triggers and PostgreSQL MODE do not make an application a real PostgreSQL deployment.

### 8.5. WORM

Write Once Read Many: storage where a committed record cannot be overwritten/deleted during its retention period.

WORM is a strong storage control, but does not replace:

- correct canonicalization;
- authorization;
- backups;
- evidence that the original event is true.

### 8.6. PITR

Point-In-Time Recovery restores a database to a particular moment using backups and transaction logs.

PITR helps after deletion/corruption, but does not always establish which version is honest. It is a recovery control complementing tamper evidence.

### 8.7. CDC

Change Data Capture: a stream of database changes. It can deliver inserts/updates to an external verifier. CDC also requires reconciliation: an administrator can change its configuration or retention.

### 8.8. Immutable audit storage

Audit evidence is stored in a domain the primary database attacker does not control. Options include immutable object storage, a confidential ledger, a transparency log, or an independent append-only service.

### 8.9. SIEM

Security Information and Event Management: centralized collection and analysis of security logs/alerts. It supports incidents, suspicious MAC failures, KMS calls, and privilege changes.

### 8.10. WAL / transaction log

**WAL** means Write-Ahead Log. The database first records a description of the change in the log, then considers the transaction durable, updating the main data pages later.

WAL supports crash recovery, replication, and PITR. It is not the same as an application audit log: WAL is optimized for database operation, its retention is limited, and an external auditor may not understand its records' meaning.

See [PostgreSQL: Write-Ahead Logging](https://www.postgresql.org/docs/current/wal-intro.html). MySQL's binary log serves a related PITR purpose, but is not an identical internal mechanism.

### 8.11. RPO and RTO

- **RPO**, Recovery Point Objective: acceptable data loss expressed as time, for example "no more than five minutes";
- **RTO**, Recovery Time Objective: how quickly operation must be restored, for example "within one hour."

HMAC and Merkle primarily address tamper evidence. Backups, replicas, WAL/PITR, and disaster-recovery procedures are needed to meet RPO/RTO after deletion or failure.

Official definitions: [NIST RPO](https://csrc.nist.gov/glossary/term/recovery_point_objective) and [NIST RTO](https://csrc.nist.gov/glossary/term/recovery_time_objective).

### 8.12. SLA and SLO

- **SLO**: an internal measurable reliability or performance target, such as 99.9% successful verifications per month;
- **SLA**: an external customer agreement, usually with consequences for a breach;
- **SLI**: the specific measure used to assess an SLO, such as p95 latency or the proportion of successful requests.

For this project, measuring a baseline before setting SLOs is sensible. A number invented in advance is not evidence.

The agreed initial target is **20 completed transfers per second (20 TPS)**. This is a requirement for a future test, not a measured result. Counted operations include verification, correct postings, and final evidence; accepting 20 requests into a queue does not mean completing 20 transfers. The latency limit is refined after the baseline, and sustained queue growth means the system is not keeping up with the load.

See [Google SRE: Service Level Objectives](https://sre.google/sre-book/service-level-objectives/).

## 9. Products and competitive context

### 9.1. Amazon QLDB

Amazon Quantum Ledger Database was a managed ledger database with cryptographically verifiable history.

AWS officially specified end of support as **July 31, 2025**. Source: [AWS QLDB notice](https://docs.aws.amazon.com/qldb/latest/developerguide/getting-started-step-7.html).

What can be said:

- a significant managed product left the market;
- existing users faced a migration task;
- a portable integrity overlay for relational databases may be relevant.

What cannot yet be said without comprehensive research:

- that the market is entirely empty;
- that the AWS-recommended architecture has no tamper-evidence options at all;
- that our PoC is already a functional QLDB replacement.

### 9.2. Aurora PostgreSQL

AWS's managed PostgreSQL-compatible database. AWS published migration/audit patterns following QLDB.

Ordinary history tables, triggers, CDC, and logging do not automatically equal a cryptographically verifiable ledger. A concrete comparison must examine the entire proposed AWS architecture, not one feature.

### 9.3. Azure SQL Ledger

An Azure SQL Database/SQL Server feature that preserves history and uses hashes, Merkle structures, and database digests for tamper evidence, including privileged-user scenarios.

Digests should be stored outside the database itself. Official overview: [Microsoft Ledger overview](https://learn.microsoft.com/en-us/sql/relational-databases/security/ledger/ledger-overview).

"No additional charge" should not be used without checking the current complete bill:

- compute/database tier;
- additional history/storage;
- immutable blob storage;
- Azure Confidential Ledger;
- automation/verification.

### 9.4. Ledger database

A database whose product features include built-in verifiable/immutable history. Examples and properties differ; not all ledger databases should be treated as equivalent.

### 9.5. Vendor lock-in

The cost and difficulty of leaving a particular provider/API/data format. A portable overlay may reduce lock-in, but a custom module introduces operational and maintenance costs. A comparison must include both sides.

## 10. Regulatory references

### 10.1. CFR

**CFR**, Code of Federal Regulations, is the codification of United States federal regulations.

The number before CFR identifies the Title, followed by a Part/Rule.

### 10.2. SEC Rule 17a-4

The correct name is **SEC Rule 17a-4**, not "17 A 4" as three separate technologies.

The rule concerns preservation of certain broker-dealer records. Following the 2022 amendments, electronic recordkeeping may use WORM or the audit-trail alternative when its requirements are met. Official short guide: [SEC amendments guide](https://www.sec.gov/investment/amendments-electronic-recordkeeping-requirements-broker-dealers).

Important distinctions:

- the rule does not mean every record must have an Ed25519 signature;
- our module may support integrity/audit-trail requirements;
- compliance depends on record scope, retention, recreation, access, export, procedures, and other controls;
- a compliance claim requires specialist legal/compliance review.

### 10.3. FDA 21 CFR Part 11

The correct name is **Title 21 CFR Part 11**. This was probably what was heard as "61/62/5/6 C."

Part 11 defines the conditions under which FDA considers electronic records and electronic signatures trustworthy, reliable, and generally equivalent to paper. It applies in the context of records required by FDA predicate rules.

Its requirements extend beyond cryptography:

- authorized access;
- system validation;
- audit trails;
- record retention/copying;
- operational/authority checks;
- electronic-signature controls;
- policies and training.

Official sources:

- [eCFR 21 CFR Part 11](https://www.ecfr.gov/current/title-21/chapter-I/subchapter-A/part-11)
- [FDA Scope and Application guidance](https://www.fda.gov/regulatory-information/search-fda-guidance-documents/part-11-electronic-records-electronic-signatures-scope-and-application)

Our project can be a control component, but not a "Part 11 compliant system" on its own.

### 10.4. RFC

**RFC**, Request for Comments, is a series of technical documents from the Internet Engineering Task Force and related streams.

Each RFC has a number, such as RFC 2104. Not every RFC is a mandatory Internet Standard: documents have categories and statuses. An article should explain whether we implement a standard literally or only borrow its construction.

### 10.5. RFC 3161

[RFC 3161](https://www.rfc-editor.org/rfc/rfc3161) describes the Time-Stamp Protocol.

The client sends a hash, and a trusted Time Stamping Authority issues a signed token binding that hash to a time.

Given trust in the TSA, this establishes that the commitment existed no later than the stated time. It does not prove the truth of the business content.

## 11. B2B and EB-2 NIW

### 11.1. B2B

**B2B** means business-to-business: a product or service targets other organizations rather than individual consumers.

For this project, a B2B model could mean a library, sidecar, or managed integrity service for banks, fintech, healthcare, and other companies. B2B is a market characteristic, not an immigration category or evidence of national importance.

### 11.2. EB-2 NIW

If "B2B National Interveyer" referred to **EB-2 National Interest Waiver (NIW)**:

- EB-2 is the employment-based second preference immigrant classification;
- NIW permits requesting a waiver of the job-offer/labor-certification requirement;
- the project and article may provide evidence about a specific proposed endeavor, its merit/impact, and whether the author is well positioned;
- a demo, its "novelty," or mentioning a regulation does not independently guarantee approval.

USCIS evaluates the specific proposed endeavor and applies a multifactor analysis. Current official context: [USCIS Policy Manual, Volume 6, Part F, Chapter 5](https://www.uscis.gov/policy-manual/volume-6-part-f-chapter-5).

Technical documents should be understandable to non-specialists, while immigration/legal strategy should be agreed with a qualified professional.

## 12. Common inaccurate statements

| Do not say | More precise wording |
|---|---|
| HMAC encrypts the record | HMAC verifies integrity/authenticity, but does not hide data |
| This is tokenization | This is HMAC-based integrity authentication |
| UUID detects deletion | UUID identifies; deletion detection requires an anchored sequence/tree/log |
| Auto-increment proves deletion | A gap is only a signal; rollback also creates gaps |
| A Merkle tree prevents deletion | A signed, externally anchored root allows changes to be detected relative to a checkpoint |
| Merkle fully removes the ordering bottleneck | It improves proofs and batching, but leaves still need an order |
| A digital signature guarantees legal non-repudiation | It provides public verification; legal effect depends on custody, identity, time, and policy |
| The database administrator can do nothing | Primary-only compromise is insufficient for forged settlement; DoS remains possible |
| Append-only means immutable | Enforcement and independent tamper evidence are needed |
| The broker provides exactly-once | Business exactly-once is usually built on idempotency and an atomic unique effect |
| Azure SQL Ledger is free | Pricing and additional resources must be checked for the specific configuration |
| We replaced QLDB | We are investigating a portable integrity layer for relational systems following QLDB's departure |
| SEC/FDA require our algorithm | Our control may support some requirements, but does not provide automatic compliance |

## 13. Abbreviation quick reference

| Term | Expansion | In one line |
|---|---|---|
| ACID | Atomicity, Consistency, Isolation, Durability | Basic database-transaction properties |
| API | Application Programming Interface | A way for programs to call each other |
| B2B | Business-to-Business | A product or service for other organizations |
| CAS | Compare-and-Set | Atomic change only when the expected version matches |
| CDC | Change Data Capture | A stream of database changes |
| CFR | Code of Federal Regulations | The codified federal regulations of the United States |
| DB/DBMS | Database / Database Management System | A database and its management system |
| DBA | Database Administrator | The database administrator |
| DDL | Data Definition Language | CREATE/ALTER/DROP of schema objects |
| DLQ | Dead-Letter Queue | Messages left unprocessed after retries |
| DML | Data Manipulation Language | SELECT/INSERT/UPDATE/DELETE of data |
| DoS | Denial of Service | An attack or failure that removes service availability |
| EB-2 | Employment-Based Second Preference | The second U.S. employment-based immigrant classification |
| HMAC | Hash-based Message Authentication Code | A MAC based on a hash and secret key |
| HSM | Hardware Security Module | Protected hardware for keys |
| HTTP | Hypertext Transfer Protocol | The request/response protocol used by web APIs |
| IAM | Identity and Access Management | Management of identities and permissions |
| JCS | JSON Canonicalization Scheme | Canonical JSON under RFC 8785 |
| JVM | Java Virtual Machine | The Java application runtime |
| KMS | Key Management Service/System | Management of keys and cryptographic operations |
| MAC | Message Authentication Code | A secret-key integrity/authenticity tag |
| mTLS | Mutual Transport Layer Security | TLS with mutual client/server authentication |
| NFC | Normalization Form C | A Unicode normalization form |
| NIW | National Interest Waiver | An immigration waiver within EB-2 |
| PITR | Point-In-Time Recovery | Restoring a database to a point in time |
| PoC | Proof of Concept | Testing an idea's viability, not yet a production system |
| RBAC | Role-Based Access Control | Access through roles |
| REST | Representational State Transfer | A common HTTP API style |
| RFC | Request for Comments | A series of Internet technical documents |
| SIEM | Security Information and Event Management | Collection and analysis of security events |
| SLA | Service Level Agreement | An external service-level agreement |
| SLI | Service Level Indicator | A measurable service-level indicator |
| SLO | Service Level Objective | A target value for a service indicator |
| STH | Signed Tree Head | Signed state of a Merkle log |
| TLV | Tag/Type-Length-Value | An unambiguous binary field structure |
| TSA | Time Stamping Authority | A trusted timestamp service |
| TOCTOU | Time Of Check To Time Of Use | Substitution between checking and using data |
| TLS | Transport Layer Security | A protected network channel |
| UTC | Coordinated Universal Time | A common time scale |
| UUID | Universally Unique Identifier | A 128-bit identifier |
| RPO | Recovery Point Objective | Acceptable data loss expressed as time |
| RTO | Recovery Time Objective | Target recovery time |
| WAL | Write-Ahead Log | The database log for durability and recovery |
| WORM | Write Once Read Many | Non-rewritable storage |

## 14. Recommended learning order

1. Sections 1–3: understand HMAC and canonical bytes.
2. Section 7: understand why correct cryptography cannot rescue poor transaction processing.
3. Sections 5–6: deletion detection, Merkle, keys, and trust domains.
4. Section 8: database permissions and recovery.
5. Sections 9–11: the market, SEC/FDA, and possible EB-2 NIW context.
6. Then read master-plan.md and discuss the open decisions.

## 15. Main primary sources

- [RFC 2104 — HMAC](https://www.rfc-editor.org/rfc/rfc2104)
- [RFC 8785 — JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785)
- [RFC 8032 — Ed25519/EdDSA](https://www.rfc-editor.org/rfc/rfc8032)
- [RFC 3161 — Time-Stamp Protocol](https://www.rfc-editor.org/rfc/rfc3161)
- [RFC 8446 — TLS 1.3](https://www.rfc-editor.org/rfc/rfc8446.html)
- [RFC 9162 — Certificate Transparency v2](https://www.rfc-editor.org/rfc/rfc9162)
- [NIST HMAC glossary](https://csrc.nist.gov/glossary/term/hmac)
- [AWS KMS HMAC keys](https://docs.aws.amazon.com/kms/latest/developerguide/hmac-create-key.html)
- [AWS QLDB end-of-support notice](https://docs.aws.amazon.com/qldb/latest/developerguide/getting-started-step-7.html)
- [Microsoft Ledger overview](https://learn.microsoft.com/en-us/sql/relational-databases/security/ledger/ledger-overview)
- [Microsoft Ledger digest management](https://learn.microsoft.com/en-us/sql/relational-databases/security/ledger/ledger-digest-management)
- [Trillian transparency log overview](https://google.github.io/trillian/)
- [SEC Rule 17a-4 amendments guide](https://www.sec.gov/investment/amendments-electronic-recordkeeping-requirements-broker-dealers)
- [eCFR 21 CFR Part 11](https://www.ecfr.gov/current/title-21/chapter-I/subchapter-A/part-11)
- [FDA Part 11 guidance](https://www.fda.gov/regulatory-information/search-fda-guidance-documents/part-11-electronic-records-electronic-signatures-scope-and-application)
- [USCIS EB-2 NIW policy context](https://www.uscis.gov/policy-manual/volume-6-part-f-chapter-5)
- [PostgreSQL — Write-Ahead Logging](https://www.postgresql.org/docs/current/wal-intro.html)
- [NIST — Recovery Point Objective](https://csrc.nist.gov/glossary/term/recovery_point_objective)
- [NIST — Recovery Time Objective](https://csrc.nist.gov/glossary/term/recovery_time_objective)
- [Google SRE — Service Level Objectives](https://sre.google/sre-book/service-level-objectives/)

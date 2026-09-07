CREATE TABLE accounts (
 id UUID PRIMARY KEY, name VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','HELD')),
 balance_cents BIGINT NOT NULL DEFAULT 0,
 CHECK (balance_cents >= 0 OR id = '00000000-0000-0000-0000-000000000001')
);
INSERT INTO accounts(id,name,status,balance_cents) VALUES ('00000000-0000-0000-0000-000000000001','Demo funding treasury','ACTIVE',0);
CREATE TABLE operation_jobs (
 operation_id UUID PRIMARY KEY, status VARCHAR(16) NOT NULL DEFAULT 'READY',
 attempts INTEGER NOT NULL DEFAULT 0, lease_until_micros BIGINT NOT NULL DEFAULT 0,
 next_attempt_micros BIGINT NOT NULL DEFAULT 0, last_error VARCHAR(512)
);
CREATE INDEX operation_jobs_ready ON operation_jobs(status,next_attempt_micros);
CREATE TABLE operation_results (
 operation_id UUID PRIMARY KEY, status VARCHAR(24) NOT NULL, reason VARCHAR(512),
 content_hash VARCHAR(64), signed_json TEXT, completed_at_micros BIGINT NOT NULL
);
CREATE TABLE ledger_journal (
 operation_id UUID PRIMARY KEY, content_hash VARCHAR(64) NOT NULL,
 operation_type VARCHAR(16) NOT NULL, related_operation_id UUID,
 completed_at_micros BIGINT NOT NULL
);
CREATE UNIQUE INDEX one_reversal_per_operation ON ledger_journal(related_operation_id,operation_type);
CREATE TABLE ledger_postings (
 operation_id UUID NOT NULL REFERENCES ledger_journal(operation_id), leg INTEGER NOT NULL CHECK(leg IN (0,1)),
 account_id UUID NOT NULL REFERENCES accounts(id), amount_cents BIGINT NOT NULL,
 PRIMARY KEY(operation_id,leg)
);
CREATE TABLE settlement_outbox (
 id UUID PRIMARY KEY, operation_id UUID NOT NULL, status VARCHAR(24) NOT NULL,
 reason VARCHAR(512), content_hash VARCHAR(64), created_at_micros BIGINT NOT NULL,
 relayed_at_micros BIGINT
);
CREATE INDEX settlement_outbox_pending ON settlement_outbox(relayed_at_micros);
CREATE TABLE account_audit_outbox (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES accounts(id),
 event_type VARCHAR(24) NOT NULL, created_at_micros BIGINT NOT NULL, relayed_at_micros BIGINT
);
CREATE INDEX account_audit_outbox_pending ON account_audit_outbox(relayed_at_micros);

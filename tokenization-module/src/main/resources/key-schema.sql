CREATE TABLE IF NOT EXISTS issuance_receipts (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    id UUID PRIMARY KEY,
    ledger_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    business_hash CHAR(64) NOT NULL,
    signed_json TEXT NOT NULL,
    issued_at_micros BIGINT NOT NULL,
    CONSTRAINT issuance_idempotency UNIQUE (ledger_id, idempotency_key)
);

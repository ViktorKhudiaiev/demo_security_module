-- The primary-DB administrator can alter this store; it is not authoritative.
CREATE TABLE accounts (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL, created_at_micros BIGINT NOT NULL);
CREATE TABLE transactions (
    id UUID PRIMARY KEY, operation_json TEXT NOT NULL, key_id VARCHAR(128) NOT NULL,
    content_hash CHAR(64) NOT NULL, mac CHAR(64) NOT NULL, created_at_micros BIGINT NOT NULL
);
CREATE TABLE transaction_outbox (
    id UUID PRIMARY KEY, transaction_id UUID NOT NULL, event_type VARCHAR(64) NOT NULL,
    created_at_micros BIGINT NOT NULL, processed_at_micros BIGINT
);
CREATE INDEX outbox_pending ON transaction_outbox(processed_at_micros,created_at_micros);
CREATE TABLE transaction_status_events (
    id UUID PRIMARY KEY, transaction_id UUID NOT NULL, status VARCHAR(32) NOT NULL,
    reason VARCHAR(1000), created_at_micros BIGINT NOT NULL
);
CREATE INDEX status_by_operation ON transaction_status_events(transaction_id,created_at_micros);

CREATE TABLE IF NOT EXISTS notification_outbox (
    operation_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_seq BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL,
    created_at_micros BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_micros BIGINT NOT NULL DEFAULT 0,
    lease_until_micros BIGINT NOT NULL DEFAULT 0,
    claim_token UUID,
    delivered_at_micros BIGINT,
    last_error VARCHAR(128)
);
CREATE INDEX IF NOT EXISTS notification_due
    ON notification_outbox(delivered_at_micros, next_attempt_micros, lease_until_micros);

CREATE TABLE IF NOT EXISTS accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY,
    from_account_id UUID NOT NULL,
    to_account_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    token VARCHAR(128) NOT NULL,
    CONSTRAINT fk_transaction_from_account FOREIGN KEY (from_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transaction_to_account FOREIGN KEY (to_account_id) REFERENCES accounts (id),
    CONSTRAINT ck_transaction_amount_positive CHECK (amount_cents > 0)
);

CREATE TABLE IF NOT EXISTS transaction_status_events (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_transaction_status_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    transaction_id UUID,
    amount_cents BIGINT NOT NULL,
    entry_type VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_ledger_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

CREATE TABLE IF NOT EXISTS transaction_outbox (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_outbox_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

DROP TRIGGER IF EXISTS transaction_pending_trigger;

CREATE TRIGGER transaction_pending_trigger
AFTER INSERT ON transactions
FOR EACH ROW
CALL "com.demo.securetransfer.security.trigger.TransactionOutboxTrigger";

DROP TRIGGER IF EXISTS transactions_no_update_trigger;
DROP TRIGGER IF EXISTS transactions_no_delete_trigger;

CREATE TRIGGER transactions_no_update_trigger
BEFORE UPDATE ON transactions
FOR EACH ROW
CALL "com.demo.securetransfer.security.trigger.TransactionsImmutableTrigger";

CREATE TRIGGER transactions_no_delete_trigger
BEFORE DELETE ON transactions
FOR EACH ROW
CALL "com.demo.securetransfer.security.trigger.TransactionsImmutableTrigger";

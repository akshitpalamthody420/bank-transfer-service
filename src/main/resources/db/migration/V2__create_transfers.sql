CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    from_account_id UUID NOT NULL REFERENCES accounts(id),
    to_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0 AND amount <= 999999999999.99),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (from_account_id <> to_account_id)
);
CREATE INDEX transfers_from_history ON transfers (from_account_id, created_at DESC, id DESC);
CREATE INDEX transfers_to_history ON transfers (to_account_id, created_at DESC, id DESC);

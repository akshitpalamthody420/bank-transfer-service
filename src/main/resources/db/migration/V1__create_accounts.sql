CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_name VARCHAR(100) NOT NULL CHECK (length(trim(owner_name)) > 0),
    currency CHAR(3) NOT NULL DEFAULT 'GBP' CHECK (currency = 'GBP'),
    balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0 AND balance <= 999999999999.99),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

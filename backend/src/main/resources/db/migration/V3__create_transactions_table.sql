CREATE TABLE transactions (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    source_wallet_id  UUID            NOT NULL REFERENCES wallets(id),
    target_wallet_id  UUID            NOT NULL REFERENCES wallets(id),
    amount            DECIMAL(19, 4)  NOT NULL,
    currency          VARCHAR(3)      NOT NULL,
    status            VARCHAR(50)     NOT NULL DEFAULT 'COMPLETED',
    idempotency_key   VARCHAR(255)    UNIQUE,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_idempotency_key ON transactions(idempotency_key);

CREATE TABLE wallets (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID            NOT NULL UNIQUE REFERENCES users(id),
    balance     DECIMAL(19, 4)  NOT NULL DEFAULT 0.0000,
    currency    VARCHAR(3)      NOT NULL DEFAULT 'EUR',
    version     BIGINT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

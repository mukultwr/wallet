-- Wallet balances — one row per wallet, locked per-row on deduction
CREATE TABLE wallet_balances (
    wallet_id   VARCHAR(36)  PRIMARY KEY,
    balance     BIGINT       NOT NULL DEFAULT 0 CHECK (balance >= 0),  -- stored in paise; DB-level guard
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Immutable append-only ledger — never UPDATE or DELETE rows here
CREATE TABLE transactions (
    id               VARCHAR(36)  PRIMARY KEY,
    wallet_id        VARCHAR(36)  NOT NULL,
    type             VARCHAR(20)  NOT NULL CHECK (type IN ('TOPUP', 'DEDUCT', 'REVERSAL')),
    amount           BIGINT       NOT NULL CHECK (amount > 0),
    balance_after    BIGINT       NOT NULL CHECK (balance_after >= 0),
    idempotency_key  VARCHAR(255) UNIQUE,          -- NULL allowed for non-idempotent ops (topup)
    reference_id     VARCHAR(255),                 -- order_id or any external ref
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Fast queries: all transactions for a wallet, newest first
CREATE INDEX idx_txn_wallet_created ON transactions (wallet_id, created_at DESC);

-- Outbox for future async event publishing (Kafka/SQS) — zero code change to wire up later
CREATE TABLE outbox_events (
    id           VARCHAR(36)  PRIMARY KEY,
    wallet_id    VARCHAR(36)  NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      TEXT         NOT NULL,
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published, created_at) WHERE published = FALSE;

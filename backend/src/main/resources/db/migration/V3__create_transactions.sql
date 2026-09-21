-- V3: lịch sử giao dịch
-- TOP_UP: chỉ có destination | WITHDRAW: chỉ có source | TRANSFER: có cả hai
CREATE TABLE transactions (
    id                    VARCHAR(36)   PRIMARY KEY DEFAULT (UUID()),
    reference_id          VARCHAR(100)  NOT NULL UNIQUE,   -- chống gửi trùng (idempotency)
    type                  VARCHAR(20)   NOT NULL,          -- TOP_UP, TRANSFER, WITHDRAW
    status                VARCHAR(20)   NOT NULL,          -- PENDING, SUCCESS, FAILED
    source_wallet_id      VARCHAR(36)   NULL,
    destination_wallet_id VARCHAR(36)   NULL,
    amount                DECIMAL(19,4) NOT NULL,
    description           VARCHAR(255)  NULL,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_txn_source FOREIGN KEY (source_wallet_id)      REFERENCES wallets(id),
    CONSTRAINT fk_txn_dest   FOREIGN KEY (destination_wallet_id) REFERENCES wallets(id),
    CONSTRAINT chk_txn_amount CHECK (amount > 0),
    CONSTRAINT chk_txn_has_wallet CHECK (source_wallet_id IS NOT NULL OR destination_wallet_id IS NOT NULL),
    CONSTRAINT chk_txn_not_same CHECK (
        source_wallet_id IS NULL
        OR destination_wallet_id IS NULL
        OR source_wallet_id <> destination_wallet_id
    )
);

-- Lấy lịch sử của một ví theo thời gian
CREATE INDEX idx_txn_source_created ON transactions(source_wallet_id, created_at);
CREATE INDEX idx_txn_dest_created   ON transactions(destination_wallet_id, created_at);

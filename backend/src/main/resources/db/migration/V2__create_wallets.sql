-- V2: bảng ví điện tử, mỗi user có đúng 1 ví (user_id UNIQUE)
CREATE TABLE wallets (
    id         VARCHAR(36)   PRIMARY KEY DEFAULT (UUID()),
    user_id    VARCHAR(36)   NOT NULL UNIQUE,
    balance    DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    currency   VARCHAR(10)   NOT NULL DEFAULT 'VND',
    status     VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE, LOCKED
    version    INT           NOT NULL DEFAULT 0,            -- optimistic lock
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_wallet_user    FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_wallet_balance CHECK (balance >= 0)
);

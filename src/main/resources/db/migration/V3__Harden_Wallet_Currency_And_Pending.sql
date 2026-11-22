UPDATE wallets SET pending = 0 WHERE pending IS NULL;

ALTER TABLE wallets
  MODIFY COLUMN pending INT NOT NULL DEFAULT 0,
  ADD CONSTRAINT chk_wallet_pending CHECK (pending >= 0);

ALTER TABLE wallets
  DROP CHECK chk_wallet_currency,
  ADD CONSTRAINT chk_wallet_currency CHECK (currency = 'VND');

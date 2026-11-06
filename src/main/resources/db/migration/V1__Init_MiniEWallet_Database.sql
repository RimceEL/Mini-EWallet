CREATE TABLE `users` (
  `id` varchar(36) NOT NULL DEFAULT (uuid()),
  `email` varchar(255) NOT NULL,
  `username` varchar(255) DEFAULT NULL,
  `password` varchar(255) NOT NULL,
  `role` varchar(50) NOT NULL DEFAULT 'USER',
  `full_name` varchar(255) DEFAULT NULL,
  `phone_number` varchar(20) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `is_active` tinyint DEFAULT '1',
  `is_verified` tinyint DEFAULT '0',
  `created_at` timestamp DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `wallets` (
  `id` varchar(36) NOT NULL DEFAULT (uuid()),
  `user_id` varchar(36) NOT NULL,
  `balance` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `currency` varchar(10) NOT NULL DEFAULT 'VND',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `version` int NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pending` int DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_id` (`user_id`),
  CONSTRAINT `fk_wallet_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_wallet_balance` CHECK (`balance` >= 0),
  CONSTRAINT `chk_wallet_status` CHECK (`status` IN ('ACTIVE', 'LOCKED', 'PENDING')),
  CONSTRAINT `chk_wallet_currency` CHECK (`currency` IN ('VND', 'USD')),
  CONSTRAINT `chk_wallet_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `transactions` (
  `id` varchar(36) NOT NULL DEFAULT (uuid()),
  `reference_id` varchar(100) NOT NULL,
  `type` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  `source_wallet_id` varchar(36) DEFAULT NULL,
  `destination_wallet_id` varchar(36) DEFAULT NULL,
  `amount` decimal(19,4) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `reference_id` (`reference_id`),
  KEY `fk_txn_source` (`source_wallet_id`),
  KEY `fk_txn_dest` (`destination_wallet_id`),
  KEY `idx_txn_source_created` (`source_wallet_id`, `created_at`),
  KEY `idx_txn_dest_created` (`destination_wallet_id`, `created_at`),
  KEY `idx_transactions_status_created` (`status`, `created_at`),
  CONSTRAINT `fk_txn_source` FOREIGN KEY (`source_wallet_id`) REFERENCES `wallets` (`id`),
  CONSTRAINT `fk_txn_dest` FOREIGN KEY (`destination_wallet_id`) REFERENCES `wallets` (`id`),
  CONSTRAINT `chk_txn_amount` CHECK (`amount` > 0),
  CONSTRAINT `chk_txn_has_wallet` CHECK (`source_wallet_id` IS NOT NULL OR `destination_wallet_id` IS NOT NULL),
  CONSTRAINT `chk_txn_wallets_by_type` CHECK (
    (`type` IN ('TOP_UP', 'WITHDRAW') AND `source_wallet_id` IS NOT NULL AND `source_wallet_id` = `destination_wallet_id`)
    OR
    (`type` = 'TRANSFER' AND `source_wallet_id` IS NOT NULL AND `destination_wallet_id` IS NOT NULL AND `source_wallet_id` != `destination_wallet_id`)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
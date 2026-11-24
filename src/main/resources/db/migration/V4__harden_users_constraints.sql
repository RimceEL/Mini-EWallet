UPDATE `users`
SET `is_active` = 0
WHERE `is_active` IS NULL;

UPDATE `users`
SET `is_verified` = 0
WHERE `is_verified` IS NULL;

ALTER TABLE `users`
    MODIFY COLUMN `is_active` tinyint NOT NULL DEFAULT 1,
    MODIFY COLUMN `is_verified` tinyint NOT NULL DEFAULT 0,
    ADD CONSTRAINT `chk_user_role` CHECK (`role` IN ('USER', 'ADMIN'));

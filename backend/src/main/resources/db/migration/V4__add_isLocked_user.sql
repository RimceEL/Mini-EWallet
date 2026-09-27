ALTER TABLE users 
ADD is_verified TINYINT DEFAULT 0 AFTER is_active;
-- Server-side login sessions. The client holds a random token; only its
-- SHA-256 hash is stored here, so this table can't be used to impersonate
-- anyone. A session ends when revoked (logout), after `expires_at` (absolute
-- lifetime), or when `last_seen_at` is older than the idle timeout.
--
-- All timestamps are written by the server from its own clock (UTC).

CREATE TABLE IF NOT EXISTS `user_sessions` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,

  `token_hash` BINARY(32) NOT NULL,
  `created_at` TIMESTAMP NOT NULL,
  `last_seen_at` TIMESTAMP NOT NULL,
  `expires_at` TIMESTAMP NOT NULL,
  `revoked_at` TIMESTAMP NULL DEFAULT NULL,

  `client_ip` VARCHAR(45) NULL,     -- fits IPv6
  `user_agent` VARCHAR(255) NULL,

  UNIQUE KEY `uq_user_sessions_token_hash` (`token_hash`),
  KEY `ix_user_sessions_user_id` (`user_id`),

  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
);

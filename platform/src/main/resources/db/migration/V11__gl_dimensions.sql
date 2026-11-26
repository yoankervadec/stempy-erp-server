-- General ledger, part 2 (modules/gl, docs/ARCHITECTURE.md §22.3): dimensions.
--
-- A company defines its own dimensions (STORE, PROJECT, ...) and their values
-- (STORE = 012 "Downtown"). A journal line carries a dimension SET: an
-- immutable, shared combination such as {STORE=012, PROJECT=P-2026-014},
-- stored once and reused by every line that has it. Reports by account never
-- touch these tables.
--
-- Dimensions and values are read and written through the generic entity
-- routes (standard columns; deleted_at stays NULL: they are disabled, never
-- deleted). Codes can't change once created.
--
-- All timestamps are UTC, written by the server.

CREATE TABLE `gl_dimensions` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  `code` VARCHAR(20) NOT NULL,                    -- STORE, PROJECT
  `name` VARCHAR(100) NOT NULL,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,

  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NOT NULL,
  `deleted_at` TIMESTAMP NULL DEFAULT NULL,

  UNIQUE KEY `uq_gl_dimensions_company_code` (`company_id`, `code`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

CREATE TABLE `gl_dimension_values` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  `dimension_id` BIGINT UNSIGNED NOT NULL,
  `code` VARCHAR(50) NOT NULL,                    -- 012, P-2026-014
  `name` VARCHAR(100) NOT NULL,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,

  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NOT NULL,
  `deleted_at` TIMESTAMP NULL DEFAULT NULL,

  -- Unique per dimension (the same code may exist in two dimensions).
  UNIQUE KEY `uq_gl_dimension_values_code` (`dimension_id`, `code`),
  KEY `ix_gl_dimension_values_company` (`company_id`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`dimension_id`) REFERENCES `gl_dimensions`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

-- A combination of (dimension, value) pairs, one value per dimension. Created
-- on first use (INSERT IGNORE on the hash, in a short transaction of its own),
-- never changed or deleted.
CREATE TABLE `gl_dimension_sets` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  -- SHA-256 (hex) of the sorted pairs 'dimensionId=valueId;...'.
  `set_hash` CHAR(64) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  UNIQUE KEY `uq_gl_dimension_sets_hash` (`company_id`, `set_hash`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`)
);

CREATE TABLE `gl_dimension_set_entries` (
  `set_id` BIGINT UNSIGNED NOT NULL,
  `dimension_id` BIGINT UNSIGNED NOT NULL,
  `value_id` BIGINT UNSIGNED NOT NULL,

  PRIMARY KEY (`set_id`, `dimension_id`),
  -- Reports by one value: "every set with STORE = 012".
  KEY `ix_gl_dimension_set_entries_value` (`value_id`, `set_id`),
  FOREIGN KEY (`set_id`) REFERENCES `gl_dimension_sets`(`id`),
  FOREIGN KEY (`dimension_id`) REFERENCES `gl_dimensions`(`id`),
  FOREIGN KEY (`value_id`) REFERENCES `gl_dimension_values`(`id`)
);

-- Per account: a dimension every line must carry (REQUIRED) or may not carry
-- (NOT_ALLOWED). No row: optional. Replaced as a whole with the account
-- (PUT /gl/accounts/{id}/dimension-rules), checked at posting.
CREATE TABLE `gl_account_dimension_rules` (
  `account_id` BIGINT UNSIGNED NOT NULL,
  `dimension_id` BIGINT UNSIGNED NOT NULL,
  `rule_type` ENUM('REQUIRED', 'NOT_ALLOWED') NOT NULL,

  PRIMARY KEY (`account_id`, `dimension_id`),
  FOREIGN KEY (`account_id`) REFERENCES `gl_accounts`(`id`),
  FOREIGN KEY (`dimension_id`) REFERENCES `gl_dimensions`(`id`)
);

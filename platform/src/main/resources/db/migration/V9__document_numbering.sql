-- Document numbering (platform.numbering, docs/ARCHITECTURE.md §21).
--
-- Code declares the kinds of document (DocumentType: key, the parameters the
-- caller supplies). These tables hold, per company, how their numbers look
-- (number_series) and how far each counter has gone (number_series_counters).
--
-- A pattern such as 'CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}' prints
-- CO-01203012346 for store 12, terminal 3, counter 12346. Every parameter in
-- the pattern separates the counter: one counter per series and per
-- combination of values (its scope).
--
-- Once a series has a counter, its pattern, mode and start value are frozen
-- (checked by the server): create a new series and make it the default.
-- Series and counters are never deleted.
--
-- To continue an old system's numbering by hand (next number = 5001):
--   INSERT INTO number_series_counters (series_id, scope, last_number) VALUES (?, '', 5000)
--     ON DUPLICATE KEY UPDATE last_number = GREATEST(last_number, VALUES(last_number));
-- Never lower last_number: numbers already issued would come back.
--
-- All timestamps are UTC, written by the server.

CREATE TABLE `number_series` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  -- DocumentType key, e.g. 'sales.customer-order'.
  `document_type` VARCHAR(100) NOT NULL,
  -- Chosen by the caller with NumberRequest.series('WEB'); the default otherwise.
  `code` VARCHAR(50) NOT NULL,
  `description` VARCHAR(255) NULL,
  `pattern` VARCHAR(255) NOT NULL,
  -- GAPLESS: the caller's transaction; a rollback gives the number back.
  -- GAPS_ALLOWED: a transaction of its own; a rollback loses the number.
  `mode` ENUM('GAPLESS', 'GAPS_ALLOWED') NOT NULL,
  -- The first number of every counter of the series.
  `start_value` BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `is_default` BOOLEAN NOT NULL DEFAULT FALSE,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,

  -- created_by NULL: created by the server (a document type's default series on first use).
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NULL,

  UNIQUE KEY `uq_number_series` (`company_id`, `document_type`, `code`),
  -- At most one default per (company, document type): NULLs never collide in a UNIQUE key.
  `default_key` VARCHAR(100) AS (IF(`is_default`, `document_type`, NULL)) STORED,
  UNIQUE KEY `uq_number_series_default` (`company_id`, `default_key`),

  CONSTRAINT `chk_number_series_start` CHECK (`start_value` >= 1),

  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

-- A counter is created with last_number = start_value - 1 in a short
-- transaction of its own, then incremented with an UPDATE: in the caller's
-- transaction for GAPLESS (the row stays locked until it commits), in a
-- transaction of its own for GAPS_ALLOWED.
CREATE TABLE `number_series_counters` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `series_id` BIGINT UNSIGNED NOT NULL,
  -- 'STORE_NO=012;TERMINAL_NO=03' (padded values, sorted by name), '' without parameters.
  `scope` VARCHAR(255) NOT NULL,
  -- The last number issued; start_value - 1 before the first.
  `last_number` BIGINT UNSIGNED NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE KEY `uq_number_series_counters` (`series_id`, `scope`),
  FOREIGN KEY (`series_id`) REFERENCES `number_series`(`id`)
);

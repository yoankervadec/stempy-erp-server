-- The company module (modules/company, docs/ARCHITECTURE.md §25): the
-- company's own settings, and its exchange rates.
--
-- Companies: the columns the generic entity routes need to change a row
-- (version, updated_at, updated_by; created_by for symmetry). The author
-- columns are NULL for the companies that exist now (made by SQL); every
-- write through the API fills updated_by.

ALTER TABLE `companies`
  ADD COLUMN `version` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `id`,
  ADD COLUMN `created_by` BIGINT UNSIGNED NULL AFTER `created_at`,
  ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER `created_by`,
  ADD COLUMN `updated_by` BIGINT UNSIGNED NULL AFTER `updated_at`,
  ADD CONSTRAINT `fk_companies_created_by` FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  ADD CONSTRAINT `fk_companies_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`);

-- Exchange rates: what one unit of a currency is worth in the company's base
-- currency (companies.base_currency_code) from a starting date until the next
-- rate of that currency starts. 1 USD = 1.3650000000 CAD is (USD, 1.365).
--
-- Per company: each company keeps its own rates (its own source, its own base
-- currency). Never one for the base currency itself (always 1; checked by
-- the server). One live rate per currency and starting date: deleted rows
-- (deleted_at set) leave `live_key` NULL, which the unique key ignores, so a
-- rate deleted by mistake can be entered again.

CREATE TABLE `exchange_rates` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  `currency_id` BIGINT UNSIGNED NOT NULL,
  `starting_date` DATE NOT NULL,
  `rate` DECIMAL(20, 10) NOT NULL,

  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NOT NULL,
  `deleted_at` DATETIME NULL DEFAULT NULL,

  `live_key` TINYINT GENERATED ALWAYS AS (CASE WHEN `deleted_at` IS NULL THEN 1 END) STORED,

  CONSTRAINT `chk_exchange_rates_rate` CHECK (`rate` > 0),
  UNIQUE KEY `uq_exchange_rates_live` (`company_id`, `currency_id`, `starting_date`, `live_key`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`currency_id`) REFERENCES `currencies`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

-- Currencies and units of measure as master data (modules/masterdata/currencies,
-- modules/masterdata/units, docs/ARCHITECTURE.md §24).
--
-- Both tables exist since V1 (install-wide: every company shares them) with
-- only id, code, created_at and is_enabled. They gain the standard columns so
-- the generic entity routes can read and write them (StandardEntity.defineGlobal):
-- version, created_by, updated_at, updated_by, deleted_at.
--
-- created_by / updated_by are NULL for the rows that existed before this
-- migration (nobody recorded who added them); every write through the API
-- fills them. Neither table is ever deleted from: rows are disabled instead
-- (deleted_at stays NULL).
--
-- Currencies: the code is an ISO 4217 code ('CAD'), fixed once created
-- (companies.base_currency_code and gl_journal_entries.currency_code hold it
-- by value). The name is new; existing rows start with their code.

ALTER TABLE `currencies`
  MODIFY `code` CHAR(3) NOT NULL,
  ADD COLUMN `version` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `id`,
  ADD COLUMN `name` VARCHAR(100) NULL AFTER `code`,
  MODIFY `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN `created_by` BIGINT UNSIGNED NULL AFTER `created_at`,
  ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER `created_by`,
  ADD COLUMN `updated_by` BIGINT UNSIGNED NULL AFTER `updated_at`,
  ADD COLUMN `deleted_at` DATETIME NULL DEFAULT NULL AFTER `updated_by`,
  ADD CONSTRAINT `fk_currencies_created_by` FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  ADD CONSTRAINT `fk_currencies_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`);

UPDATE `currencies` SET `name` = `code` WHERE `name` IS NULL;

ALTER TABLE `currencies`
  MODIFY `name` VARCHAR(100) NOT NULL;

-- Units of measure: the code ('UN', 'BOX', 'KG') can be corrected later
-- (products point at the id). The description becomes required; existing rows
-- without one start with their code.

UPDATE `units_of_measure` SET `description` = `code` WHERE `description` IS NULL;

ALTER TABLE `units_of_measure`
  ADD COLUMN `version` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `id`,
  MODIFY `description` VARCHAR(255) NOT NULL,
  MODIFY `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN `created_by` BIGINT UNSIGNED NULL AFTER `created_at`,
  ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER `created_by`,
  ADD COLUMN `updated_by` BIGINT UNSIGNED NULL AFTER `updated_at`,
  ADD COLUMN `deleted_at` DATETIME NULL DEFAULT NULL AFTER `updated_by`,
  ADD CONSTRAINT `fk_units_of_measure_created_by` FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  ADD CONSTRAINT `fk_units_of_measure_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`);

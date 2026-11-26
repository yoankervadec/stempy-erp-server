-- General ledger, part 1 (modules/gl, docs/ARCHITECTURE.md §22): the chart of
-- accounts, fiscal years and their periods. Everything is per company.
--
-- Accounts are read through the generic entity routes (standard columns:
-- version, created/updated at/by, deleted_at, always NULL here: accounts are
-- disabled, never deleted) and written by the gl module's own rules.
--
-- A fiscal year is created explicitly and cut into 12 monthly periods. Each
-- new year starts the day after the company's previous one ends; the first may
-- start on the 1st of any month. Postings must fall in an OPEN period.
--
-- All timestamps are UTC, written by the server.

CREATE TABLE `gl_accounts` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  -- Free text, unique in the company; sorted as text, so keep a fixed width.
  `account_no` VARCHAR(20) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `description` VARCHAR(255) NULL,
  -- Normal balance: debit for ASSET and EXPENSE, credit for the others.
  `account_type` ENUM('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE') NOT NULL,
  -- A heading account of the same company, for grouping in reports.
  `parent_id` BIGINT UNSIGNED NULL,
  -- A heading groups accounts and never takes postings.
  `is_heading` BOOLEAN NOT NULL DEFAULT FALSE,
  -- FALSE for control accounts (receivables, payables, tax): only the module
  -- that owns them posts there, never a manual journal entry.
  `manual_posting` BOOLEAN NOT NULL DEFAULT TRUE,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,

  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NOT NULL,
  `deleted_at` TIMESTAMP NULL DEFAULT NULL,

  UNIQUE KEY `uq_gl_accounts_company_no` (`company_id`, `account_no`),
  KEY `ix_gl_accounts_parent` (`parent_id`),
  -- No CHECK against parent_id = id: MySQL refuses CHECKs on AUTO_INCREMENT
  -- columns. The gl module refuses self-parenting and cycles.
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`parent_id`) REFERENCES `gl_accounts`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

CREATE TABLE `gl_fiscal_years` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `start_date` DATE NOT NULL,                 -- the 1st of a month
  `end_date` DATE NOT NULL,                   -- the last day of the 12th month
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL,

  UNIQUE KEY `uq_gl_fiscal_years_start` (`company_id`, `start_date`),
  CONSTRAINT `chk_gl_fiscal_years_dates` CHECK (`end_date` > `start_date`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`)
);

CREATE TABLE `gl_periods` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `fiscal_year_id` BIGINT UNSIGNED NOT NULL,
  `period_no` TINYINT UNSIGNED NOT NULL,      -- 1 to 12 within the fiscal year
  `start_date` DATE NOT NULL,
  `end_date` DATE NOT NULL,
  -- Posting locks the period FOR SHARE; closing and reopening FOR UPDATE.
  `status` ENUM('OPEN', 'CLOSED') NOT NULL DEFAULT 'OPEN',
  `version` INT UNSIGNED NOT NULL DEFAULT 0,
  -- The last close or reopen (the audit log keeps every one).
  `status_changed_at` TIMESTAMP NULL DEFAULT NULL,
  `status_changed_by` BIGINT UNSIGNED NULL,

  UNIQUE KEY `uq_gl_periods_start` (`company_id`, `start_date`),
  UNIQUE KEY `uq_gl_periods_year_no` (`fiscal_year_id`, `period_no`),
  KEY `ix_gl_periods_dates` (`company_id`, `end_date`),
  CONSTRAINT `chk_gl_periods_no` CHECK (`period_no` BETWEEN 1 AND 12),
  CONSTRAINT `chk_gl_periods_dates` CHECK (`end_date` >= `start_date`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`fiscal_year_id`) REFERENCES `gl_fiscal_years`(`id`),
  FOREIGN KEY (`status_changed_by`) REFERENCES `users`(`id`)
);

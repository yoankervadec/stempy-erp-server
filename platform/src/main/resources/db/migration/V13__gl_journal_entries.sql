-- General ledger, part 3 (modules/gl, docs/ARCHITECTURE.md §22.5): journal
-- entries and their lines.
--
-- Posted entries are immutable. The only later change is reversed_by_id, set
-- once when a reversal is posted. Corrections are reversals: a new entry with
-- debits and credits swapped, linked both ways.
--
-- Amounts are in the company's base currency (companies.base_currency_code,
-- copied to currency_code), rounded to its minor unit. Each line has exactly
-- one of debit / credit above zero; an entry's debits equal its credits.
--
-- entry_no comes from document numbering (§21): document type
-- gl.journal-entry, always GAPLESS.
--
-- All timestamps are UTC, written by the server.

CREATE TABLE `gl_journal_entries` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `entry_no` VARCHAR(50) NOT NULL,
  `posting_date` DATE NOT NULL,
  `period_id` BIGINT UNSIGNED NOT NULL,
  `description` VARCHAR(255) NOT NULL,
  -- MANUAL, or the posting module's document type (e.g. sales.invoice) with
  -- that document's id and number.
  `source_type` VARCHAR(100) NOT NULL,
  `source_id` BIGINT UNSIGNED NULL,
  `source_no` VARCHAR(50) NULL,
  `reversal_of_id` BIGINT UNSIGNED NULL,
  `reversed_by_id` BIGINT UNSIGNED NULL,
  `currency_code` CHAR(3) NOT NULL,
  -- Sum of the debits (= sum of the credits).
  `total` DECIMAL(19, 4) NOT NULL,
  `posted_at` TIMESTAMP NOT NULL,
  `posted_by` BIGINT UNSIGNED NOT NULL,

  UNIQUE KEY `uq_gl_journal_entries_no` (`company_id`, `entry_no`),
  -- An entry is reversed at most once, and a reversal reverses one entry.
  UNIQUE KEY `uq_gl_journal_entries_reversal_of` (`reversal_of_id`),
  UNIQUE KEY `uq_gl_journal_entries_reversed_by` (`reversed_by_id`),
  KEY `ix_gl_journal_entries_date` (`company_id`, `posting_date`),
  KEY `ix_gl_journal_entries_source` (`company_id`, `source_type`, `source_id`),
  CONSTRAINT `chk_gl_journal_entries_total` CHECK (`total` > 0),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`period_id`) REFERENCES `gl_periods`(`id`),
  FOREIGN KEY (`reversal_of_id`) REFERENCES `gl_journal_entries`(`id`),
  FOREIGN KEY (`reversed_by_id`) REFERENCES `gl_journal_entries`(`id`),
  FOREIGN KEY (`posted_by`) REFERENCES `users`(`id`)
);

CREATE TABLE `gl_journal_lines` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `entry_id` BIGINT UNSIGNED NOT NULL,
  `line_no` INT UNSIGNED NOT NULL,
  -- Copied from the entry, for the report index below.
  `company_id` BIGINT UNSIGNED NOT NULL,
  `posting_date` DATE NOT NULL,
  `account_id` BIGINT UNSIGNED NOT NULL,
  `debit` DECIMAL(19, 4) NOT NULL DEFAULT 0,
  `credit` DECIMAL(19, 4) NOT NULL DEFAULT 0,
  `description` VARCHAR(255) NULL,
  -- NULL: no dimensions.
  `dimension_set_id` BIGINT UNSIGNED NULL,

  UNIQUE KEY `uq_gl_journal_lines_no` (`entry_id`, `line_no`),
  -- Trial balance and account ledger: one account's lines by date.
  KEY `ix_gl_journal_lines_account` (`company_id`, `account_id`, `posting_date`),
  CONSTRAINT `chk_gl_journal_lines_sides` CHECK (
    `debit` >= 0 AND `credit` >= 0 AND ((`debit` > 0 AND `credit` = 0) OR (`credit` > 0 AND `debit` = 0))),
  FOREIGN KEY (`entry_id`) REFERENCES `gl_journal_entries`(`id`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`account_id`) REFERENCES `gl_accounts`(`id`),
  FOREIGN KEY (`dimension_set_id`) REFERENCES `gl_dimension_sets`(`id`)
);

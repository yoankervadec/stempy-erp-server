-- Customers (modules/masterdata/customers, docs/ARCHITECTURE.md §26): the
-- people and businesses a company sells to. Per company, on the generic
-- entity routes (standard columns, soft delete).
--
-- The address is the billing address. country_code is ISO 3166-1 alpha-2
-- ('CA'); province_code is the ISO 3166-2 subdivision without its country
-- ('QC'), so country_code || '-' || province_code is a tax place of supply
-- ('CA-QC', tax_jurisdictions). The server checks Canadian provinces and
-- postal codes.
--
-- One customer per number in a company, deleted ones included (like every
-- other number in the schema): a deleted customer's number is never reused,
-- so an old document or audit entry that names it stays unambiguous.

CREATE TABLE `customers` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  `customer_no` VARCHAR(20) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `contact_name` VARCHAR(100) NULL,
  `email` VARCHAR(254) NULL,
  `phone` VARCHAR(30) NULL,

  `address_line1` VARCHAR(100) NULL,
  `address_line2` VARCHAR(100) NULL,
  `city` VARCHAR(50) NULL,
  `province_code` VARCHAR(3) NULL,
  `postal_code` VARCHAR(20) NULL,
  `country_code` CHAR(2) NOT NULL DEFAULT 'CA',

  -- The currency the customer is billed in; the company's base currency unless set.
  `currency_id` BIGINT UNSIGNED NOT NULL,
  -- What documents to the customer are written in; NULL: the company's default language.
  `language` CHAR(2) NULL,
  -- In the company's base currency; NULL: no limit.
  `credit_limit` DECIMAL(19, 4) NULL,
  `notes` VARCHAR(2000) NULL,

  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NOT NULL,
  `deleted_at` DATETIME NULL DEFAULT NULL,

  CONSTRAINT `chk_customers_language` CHECK (`language` IS NULL OR `language` IN ('fr', 'en')),
  CONSTRAINT `chk_customers_credit_limit` CHECK (`credit_limit` IS NULL OR `credit_limit` >= 0),
  UNIQUE KEY `uq_customers_company_no` (`company_id`, `customer_no`),
  KEY `ix_customers_company_name` (`company_id`, `name`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`currency_id`) REFERENCES `currencies`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

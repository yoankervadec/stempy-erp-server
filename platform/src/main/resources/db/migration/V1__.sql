-- # Stempy ERP Server
--
-- ## Naming Conventions
--
-- **Tables:** `snake_case`, plural, no prefix: `sales_orders`, `journal_entries`, `gl_accounts`.
-- **Columns:** `snake_case`, singular: `order_date`, `customer_id`, `unit_price`.
-- **Primary keys:** always `id`. Not `sales_order_id` on the `sales_orders` table itself.
-- **Foreign keys:** `<referenced_table_singular>_id` — `customer_id` referencing `customers.id`, `journal_entry_id` referencing `journal_entries.id`. If a table has two FKs to the same table (e.g. `sales_orders.bill_to_customer_id` and `ship_to_customer_id`), prefix to disambiguate rather than breaking the pattern.
-- **Money columns:** `_amount` suffix, always paired with a currency column if not scoping the whole table to one currency: `total_amount`, `currency_code`. `DECIMAL(19,4)`: 4 decimal places gives headroom for unit prices and tax calculations that need more precision than the 2 decimals.
--
--
-- ## Data Types
--
-- **Dates & Time:** `TIMESTAMP`/`CURRENT_TIMESTAMP` for Date Time and `DATE` for dates.
-- **Money:** `DECIMAL(19,4)`
-- **Code:** `VARCHAR(50)` UNIQUE NOT NULL
--
--
-- ----------------------------------------------------
-- Create Database
-- ----------------------------------------------------
--
-- CREATE DATABASE `stempy_erp_dev`
--     CHARACTER SET utf8mb4
--     COLLATE utf8mb4_0900_ai_ci;
--
-- ai: accent-insensitive (é = e)
-- ci: case-insensitive (ABC = abc)
--
--
-- ----------------------------------------------------
-- Create User(s)
-- ----------------------------------------------------
--
--
-- CREATE USER 'stempy_erp'@'localhost'
--     IDENTIFIED BY ?;
-- GRANT ALL PRIVILEGES
--     ON `stempy_erp_dev`.*
--     TO 'stempy_erp'@'localhost';
--
--
-- ----------------------------------------------------
-- Generic / Config Tables
-- ----------------------------------------------------
--

CREATE TABLE IF NOT EXISTS `companies` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(50) UNIQUE NOT NULL, -- The company name
  `description` VARCHAR(255),
  -- ... more
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS `currencies` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(50) UNIQUE NOT NULL, -- CAD, USD
  -- ... more
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS `units_of_measure` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(50) UNIQUE NOT NULL, -- B2, UN, LBS
  `description` VARCHAR(255),
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS `tax_groups` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(50) UNIQUE NOT NULL, -- QC_STANDARD, QC_INDIGENOUS_GOODS
  `description` VARCHAR(255), -- Quebec standard taxable goods
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS `tax_rates` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(50) UNIQUE NOT NULL, -- GST_5, HST_13
  `description` VARCHAR(255), -- Canada Goods and Services Tax 5%, Ontario Harmonized Sales Tax 13%
  `rate` DECIMAL(9, 6) NOT NULL,
  `is_compound` BOOLEAN NOT NULL, -- tax on tax
  `calculation_order` INT NOT NULL DEFAULT 0, -- works with `is_compound` to determine which is applied first
  `valid_from_date` DATE NOT NULL,
  `valid_to_date` DATE,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL
);

-- Map `tax_rates` to `tax_groups`
CREATE TABLE IF NOT EXISTS `tax_group_lines` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `tax_group_id` BIGINT UNSIGNED NOT NULL,
  `tax_rate_id` BIGINT UNSIGNED NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0
);
--
--
-- ----------------------------------------------------
-- Business Tables
-- ----------------------------------------------------
--
-- CREATE TABLE IF NOT EXISTS `table_name` (
--   `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
--   `company_id` BIGINT UNSIGNED NOT NULL,
--   -- business columns...
--   `version` INT UNSIGNED NOT NULL DEFAULT 0,
--   `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   `created_by` BIGINT UNSIGNED NOT NULL,
--   `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
--   `updated_by` BIGINT UNSIGNED NOT NULL,
--   `deleted_at` TIMESTAMP NULL DEFAULT NULL
-- );

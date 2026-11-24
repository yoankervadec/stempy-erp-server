-- Sales tax data for the tax engine (modules/tax, docs/ARCHITECTURE.md §19),
-- and each company's base currency.
--
-- How a line's taxes are found:
--   place of supply (tax_jurisdictions, e.g. CA-ON)
--   + what is supplied (tax_categories, e.g. STANDARD)
--   + the document date
--   -> tax_rules -> a tax_group (or none: zero-rated / exempt)
--   -> its tax_rates valid on that date (tax_group_lines).
--
-- Install-wide: government rates are the same for every company.
-- Rates are PERCENT: 5.000000 is 5 %, 9.975000 is 9.975 %.
-- A rate change is a NEW tax_rates row (new code, valid_from_date) in the
-- same group, with valid_to_date set on the old one; documents already
-- issued keep the rate they stored.
--
-- The seeded rates (current as of 2026) must be checked against CRA and the
-- provinces before production use. Only the current rates are seeded:
-- documents dated before a rule's valid_from_date find no rule and fail.

-- ----------------------------------------------------
-- Companies: base currency (ISO 4217, validated by the server)
-- ----------------------------------------------------

ALTER TABLE `companies`
  ADD COLUMN `base_currency_code` CHAR(3) NOT NULL DEFAULT 'CAD' AFTER `description`;

-- ----------------------------------------------------
-- Jurisdictions: where a tax applies / where a supply is made
-- ----------------------------------------------------

-- ISO 3166 codes: CA for federal taxes, CA-QC ... for provinces and territories.
CREATE TABLE `tax_jurisdictions` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(50) NOT NULL UNIQUE,
  `name` VARCHAR(255) NOT NULL,
  `parent_id` BIGINT UNSIGNED NULL,                  -- CA-QC -> CA
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  FOREIGN KEY (`parent_id`) REFERENCES `tax_jurisdictions`(`id`)
);

-- ----------------------------------------------------
-- Categories: what kind of supply a line is
-- ----------------------------------------------------

-- ZERO_RATED and EXEMPT both charge no tax, but differ for input tax credits
-- (zero-rated supplies allow them, exempt ones do not), so they stay distinct.
CREATE TABLE `tax_categories` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(50) NOT NULL UNIQUE,
  `description` VARCHAR(255),
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE
);

-- ----------------------------------------------------
-- Rates (V1 table): who levies them, what kind, how much is recoverable
-- ----------------------------------------------------

ALTER TABLE `tax_rates`
  -- The jurisdiction that levies it: CA for GST, CA-QC for QST, CA-ON for Ontario HST.
  ADD COLUMN `jurisdiction_id` BIGINT UNSIGNED NOT NULL AFTER `description`,
  -- For returns: GST and HST go to CRA, QST to Revenu Quebec, PST/RST to the province.
  ADD COLUMN `tax_kind` ENUM('GST', 'HST', 'QST', 'PST') NOT NULL AFTER `jurisdiction_id`,
  -- Share a registered buyer can usually claim back on purchases (ITC / ITR).
  -- 100 for GST/HST/QST, 0 for PST. Restrictions per purchase are business logic.
  ADD COLUMN `recoverable_percent` DECIMAL(9, 6) NOT NULL DEFAULT 100 AFTER `is_compound`,
  ADD CONSTRAINT `fk_tax_rates_jurisdiction` FOREIGN KEY (`jurisdiction_id`) REFERENCES `tax_jurisdictions`(`id`),
  ADD CONSTRAINT `chk_tax_rates_rate` CHECK (`rate` >= 0 AND `rate` <= 100),
  ADD CONSTRAINT `chk_tax_rates_recoverable` CHECK (`recoverable_percent` >= 0 AND `recoverable_percent` <= 100),
  ADD CONSTRAINT `chk_tax_rates_validity` CHECK (`valid_to_date` IS NULL OR `valid_to_date` >= `valid_from_date`);

-- ----------------------------------------------------
-- Group lines (V1 table): the missing keys
-- ----------------------------------------------------

ALTER TABLE `tax_group_lines`
  ADD CONSTRAINT `uq_tax_group_lines` UNIQUE (`tax_group_id`, `tax_rate_id`),
  ADD CONSTRAINT `fk_tax_group_lines_group` FOREIGN KEY (`tax_group_id`) REFERENCES `tax_groups`(`id`),
  ADD CONSTRAINT `fk_tax_group_lines_rate` FOREIGN KEY (`tax_rate_id`) REFERENCES `tax_rates`(`id`);

-- ----------------------------------------------------
-- Rules: (place of supply, category, date) -> tax group
-- ----------------------------------------------------

-- Exactly one rule must apply to a (jurisdiction, category, date); the
-- engine rejects overlaps and gaps. tax_group_id NULL means "no tax"
-- (zero-rated or exempt), which is different from "no rule" (an error).
CREATE TABLE `tax_rules` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `jurisdiction_id` BIGINT UNSIGNED NOT NULL,
  `tax_category_id` BIGINT UNSIGNED NOT NULL,
  `tax_group_id` BIGINT UNSIGNED NULL,
  `valid_from_date` DATE NOT NULL,
  `valid_to_date` DATE NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY `uq_tax_rules` (`jurisdiction_id`, `tax_category_id`, `valid_from_date`),
  CONSTRAINT `chk_tax_rules_validity` CHECK (`valid_to_date` IS NULL OR `valid_to_date` >= `valid_from_date`),
  FOREIGN KEY (`jurisdiction_id`) REFERENCES `tax_jurisdictions`(`id`),
  FOREIGN KEY (`tax_category_id`) REFERENCES `tax_categories`(`id`),
  FOREIGN KEY (`tax_group_id`) REFERENCES `tax_groups`(`id`)
);

-- ====================================================
-- Seed data (verify before production use)
-- ====================================================

INSERT INTO `tax_jurisdictions` (`code`, `name`) VALUES ('CA', 'Canada');
INSERT INTO `tax_jurisdictions` (`code`, `name`, `parent_id`)
SELECT p.code, p.name, ca.id
FROM tax_jurisdictions ca
CROSS JOIN (
  SELECT 'CA-AB' AS code, 'Alberta' AS name UNION ALL
  SELECT 'CA-BC', 'British Columbia' UNION ALL
  SELECT 'CA-MB', 'Manitoba' UNION ALL
  SELECT 'CA-NB', 'New Brunswick' UNION ALL
  SELECT 'CA-NL', 'Newfoundland and Labrador' UNION ALL
  SELECT 'CA-NS', 'Nova Scotia' UNION ALL
  SELECT 'CA-NT', 'Northwest Territories' UNION ALL
  SELECT 'CA-NU', 'Nunavut' UNION ALL
  SELECT 'CA-ON', 'Ontario' UNION ALL
  SELECT 'CA-PE', 'Prince Edward Island' UNION ALL
  SELECT 'CA-QC', 'Quebec' UNION ALL
  SELECT 'CA-SK', 'Saskatchewan' UNION ALL
  SELECT 'CA-YT', 'Yukon') p
WHERE ca.code = 'CA';

INSERT INTO `tax_categories` (`code`, `description`) VALUES
  ('STANDARD', 'Taxable at the full rates of the place of supply'),
  ('ZERO_RATED', 'Taxable at 0 % (e.g. basic groceries, exports): input tax credits allowed'),
  ('EXEMPT', 'Not taxable (e.g. most residential rents): no input tax credits');

-- Rates. calculation_order only matters for compound rates (none today).
INSERT INTO `tax_rates` (`code`, `description`, `jurisdiction_id`, `tax_kind`, `rate`, `is_compound`,
    `recoverable_percent`, `calculation_order`, `valid_from_date`, `valid_to_date`, `is_enabled`)
SELECT r.code, r.description, j.id, r.kind, r.rate, FALSE, r.recoverable, r.calc_order, r.valid_from, r.valid_to, TRUE
FROM (
  SELECT 'GST_5' AS code, 'Goods and Services Tax 5 %' AS description, 'CA' AS jurisdiction, 'GST' AS kind,
         5.000000 AS rate, 100 AS recoverable, 0 AS calc_order, DATE '2008-01-01' AS valid_from, NULL AS valid_to
  UNION ALL SELECT 'QST_9_975', 'Quebec Sales Tax 9.975 %', 'CA-QC', 'QST', 9.975000, 100, 1, DATE '2013-01-01', NULL
  UNION ALL SELECT 'ON_HST_13', 'Ontario Harmonized Sales Tax 13 %', 'CA-ON', 'HST', 13.000000, 100, 0, DATE '2010-07-01', NULL
  UNION ALL SELECT 'NB_HST_15', 'New Brunswick Harmonized Sales Tax 15 %', 'CA-NB', 'HST', 15.000000, 100, 0, DATE '2016-07-01', NULL
  UNION ALL SELECT 'NL_HST_15', 'Newfoundland and Labrador Harmonized Sales Tax 15 %', 'CA-NL', 'HST', 15.000000, 100, 0, DATE '2016-07-01', NULL
  UNION ALL SELECT 'NS_HST_15', 'Nova Scotia Harmonized Sales Tax 15 %', 'CA-NS', 'HST', 15.000000, 100, 0, DATE '2010-07-01', DATE '2025-03-31'
  UNION ALL SELECT 'NS_HST_14', 'Nova Scotia Harmonized Sales Tax 14 %', 'CA-NS', 'HST', 14.000000, 100, 0, DATE '2025-04-01', NULL
  UNION ALL SELECT 'PE_HST_15', 'Prince Edward Island Harmonized Sales Tax 15 %', 'CA-PE', 'HST', 15.000000, 100, 0, DATE '2016-10-01', NULL
  UNION ALL SELECT 'BC_PST_7', 'British Columbia Provincial Sales Tax 7 %', 'CA-BC', 'PST', 7.000000, 0, 1, DATE '2013-04-01', NULL
  UNION ALL SELECT 'MB_RST_7', 'Manitoba Retail Sales Tax 7 %', 'CA-MB', 'PST', 7.000000, 0, 1, DATE '2019-07-01', NULL
  UNION ALL SELECT 'SK_PST_6', 'Saskatchewan Provincial Sales Tax 6 %', 'CA-SK', 'PST', 6.000000, 0, 1, DATE '2017-03-23', NULL
) r
JOIN tax_jurisdictions j ON j.code = r.jurisdiction;

INSERT INTO `tax_groups` (`code`, `description`, `is_enabled`) VALUES
  ('CA_GST', 'GST only (AB, NT, NU, YT)', TRUE),
  ('QC_GST_QST', 'Quebec: GST + QST', TRUE),
  ('ON_HST', 'Ontario: HST', TRUE),
  ('NB_HST', 'New Brunswick: HST', TRUE),
  ('NL_HST', 'Newfoundland and Labrador: HST', TRUE),
  ('NS_HST', 'Nova Scotia: HST', TRUE),
  ('PE_HST', 'Prince Edward Island: HST', TRUE),
  ('BC_GST_PST', 'British Columbia: GST + PST', TRUE),
  ('MB_GST_RST', 'Manitoba: GST + RST', TRUE),
  ('SK_GST_PST', 'Saskatchewan: GST + PST', TRUE);

-- A group holds every version of its rates; the document date picks one.
INSERT INTO `tax_group_lines` (`tax_group_id`, `tax_rate_id`, `sort_order`)
SELECT g.id, r.id, l.sort_order
FROM (
  SELECT 'CA_GST' AS grp, 'GST_5' AS rate, 0 AS sort_order
  UNION ALL SELECT 'QC_GST_QST', 'GST_5', 0
  UNION ALL SELECT 'QC_GST_QST', 'QST_9_975', 1
  UNION ALL SELECT 'ON_HST', 'ON_HST_13', 0
  UNION ALL SELECT 'NB_HST', 'NB_HST_15', 0
  UNION ALL SELECT 'NL_HST', 'NL_HST_15', 0
  UNION ALL SELECT 'NS_HST', 'NS_HST_15', 0
  UNION ALL SELECT 'NS_HST', 'NS_HST_14', 0
  UNION ALL SELECT 'PE_HST', 'PE_HST_15', 0
  UNION ALL SELECT 'BC_GST_PST', 'GST_5', 0
  UNION ALL SELECT 'BC_GST_PST', 'BC_PST_7', 1
  UNION ALL SELECT 'MB_GST_RST', 'GST_5', 0
  UNION ALL SELECT 'MB_GST_RST', 'MB_RST_7', 1
  UNION ALL SELECT 'SK_GST_PST', 'GST_5', 0
  UNION ALL SELECT 'SK_GST_PST', 'SK_PST_6', 1
) l
JOIN tax_groups g ON g.code = l.grp
JOIN tax_rates r ON r.code = l.rate;

-- STANDARD supplies: the group of the place of supply, from the date its
-- current rates start. ZERO_RATED and EXEMPT: no tax, everywhere.
INSERT INTO `tax_rules` (`jurisdiction_id`, `tax_category_id`, `tax_group_id`, `valid_from_date`)
SELECT j.id, c.id, g.id, s.valid_from
FROM (
  SELECT 'CA-AB' AS jurisdiction, 'CA_GST' AS grp, DATE '2008-01-01' AS valid_from
  UNION ALL SELECT 'CA-NT', 'CA_GST', DATE '2008-01-01'
  UNION ALL SELECT 'CA-NU', 'CA_GST', DATE '2008-01-01'
  UNION ALL SELECT 'CA-YT', 'CA_GST', DATE '2008-01-01'
  UNION ALL SELECT 'CA-QC', 'QC_GST_QST', DATE '2013-01-01'
  UNION ALL SELECT 'CA-ON', 'ON_HST', DATE '2010-07-01'
  UNION ALL SELECT 'CA-NB', 'NB_HST', DATE '2016-07-01'
  UNION ALL SELECT 'CA-NL', 'NL_HST', DATE '2016-07-01'
  UNION ALL SELECT 'CA-NS', 'NS_HST', DATE '2010-07-01'
  UNION ALL SELECT 'CA-PE', 'PE_HST', DATE '2016-10-01'
  UNION ALL SELECT 'CA-BC', 'BC_GST_PST', DATE '2013-04-01'
  UNION ALL SELECT 'CA-MB', 'MB_GST_RST', DATE '2019-07-01'
  UNION ALL SELECT 'CA-SK', 'SK_GST_PST', DATE '2017-03-23'
) s
JOIN tax_jurisdictions j ON j.code = s.jurisdiction
JOIN tax_groups g ON g.code = s.grp
JOIN tax_categories c ON c.code = 'STANDARD';

INSERT INTO `tax_rules` (`jurisdiction_id`, `tax_category_id`, `tax_group_id`, `valid_from_date`)
SELECT j.id, c.id, NULL, DATE '2008-01-01'
FROM tax_jurisdictions j
JOIN tax_categories c ON c.code IN ('ZERO_RATED', 'EXEMPT')
WHERE j.code <> 'CA';

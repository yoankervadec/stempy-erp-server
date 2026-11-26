-- Languages (docs/ARCHITECTURE.md §23.5): what the server writes its texts in
-- for a signed-in request (labels, translated error messages).
--
-- A request uses the user's own language when they chose one, else the
-- company's default. Codes are the platform's Language values, lowercase:
-- 'fr', 'en'.

ALTER TABLE `companies`
  ADD COLUMN `default_language` CHAR(2) NOT NULL DEFAULT 'fr' AFTER `base_currency_code`,
  ADD CONSTRAINT `chk_companies_default_language` CHECK (`default_language` IN ('fr', 'en'));

ALTER TABLE `users`
  ADD COLUMN `language` CHAR(2) NULL DEFAULT NULL AFTER `name`,
  ADD CONSTRAINT `chk_users_language` CHECK (`language` IS NULL OR `language` IN ('fr', 'en'));

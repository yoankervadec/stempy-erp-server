-- Users become install-wide and work in one or more companies; roles and
-- per-user permission overrides are granted per company.
-- See docs/ARCHITECTURE.md §16.
--
-- Permission names are plain strings declared in code (PermissionRegistry),
-- not rows of a permissions table: the code is the source of truth. Grants
-- naming a permission the code no longer declares are ignored.
--
-- created_by is NULL on rows made by the system or a CLI command (no acting
-- user).

-- ----------------------------------------------------
-- Memberships: which companies a user may work in
-- ----------------------------------------------------

CREATE TABLE `user_companies` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NULL,

  PRIMARY KEY (`user_id`, `company_id`),
  KEY `ix_user_companies_company_id` (`company_id`),
  FOREIGN KEY (`user_id`) REFERENCES `users`(`id`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`)
);

-- Every existing user keeps access to the company they belonged to.
INSERT INTO `user_companies` (`user_id`, `company_id`, `is_enabled`, `created_at`, `created_by`)
SELECT `id`, `company_id`, TRUE, `created_at`, NULL FROM `users`;

-- ----------------------------------------------------
-- users, credentials and sessions lose company_id
-- ----------------------------------------------------
--
-- The foreign keys were created unnamed in V2/V3, so their names are looked up
-- rather than assumed.

-- The company used when a request doesn't name one.
ALTER TABLE `users` ADD COLUMN `default_company_id` BIGINT UNSIGNED NULL AFTER `id`;
UPDATE `users` SET `default_company_id` = `company_id`;
ALTER TABLE `users` ADD CONSTRAINT `fk_users_default_company`
  FOREIGN KEY (`default_company_id`) REFERENCES `companies`(`id`);

SET @drop_fk = (SELECT CONCAT('ALTER TABLE `users` DROP FOREIGN KEY `', CONSTRAINT_NAME, '`')
  FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'company_id'
    AND REFERENCED_TABLE_NAME = 'companies');
PREPARE drop_fk FROM @drop_fk;
EXECUTE drop_fk;
DEALLOCATE PREPARE drop_fk;
ALTER TABLE `users` DROP COLUMN `company_id`;

SET @drop_fk = (SELECT CONCAT('ALTER TABLE `user_credentials` DROP FOREIGN KEY `', CONSTRAINT_NAME, '`')
  FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_credentials' AND COLUMN_NAME = 'company_id'
    AND REFERENCED_TABLE_NAME = 'companies');
PREPARE drop_fk FROM @drop_fk;
EXECUTE drop_fk;
DEALLOCATE PREPARE drop_fk;
ALTER TABLE `user_credentials` DROP COLUMN `company_id`;

-- A session identifies a user; the company is chosen per request.
SET @drop_fk = (SELECT CONCAT('ALTER TABLE `user_sessions` DROP FOREIGN KEY `', CONSTRAINT_NAME, '`')
  FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sessions' AND COLUMN_NAME = 'company_id'
    AND REFERENCED_TABLE_NAME = 'companies');
PREPARE drop_fk FROM @drop_fk;
EXECUTE drop_fk;
DEALLOCATE PREPARE drop_fk;
ALTER TABLE `user_sessions` DROP COLUMN `company_id`;

-- ----------------------------------------------------
-- Roles: install-wide named sets of permissions
-- ----------------------------------------------------

CREATE TABLE `roles` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  `code` VARCHAR(50) UNIQUE NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `description` VARCHAR(255) NULL,
  -- Every permission the code declares, including ones added by later releases.
  `all_permissions` BOOLEAN NOT NULL DEFAULT FALSE,

  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NULL,
  `deleted_at` TIMESTAMP NULL DEFAULT NULL,

  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

CREATE TABLE `role_permissions` (
  `role_id` BIGINT UNSIGNED NOT NULL,
  `permission` VARCHAR(150) NOT NULL,

  PRIMARY KEY (`role_id`, `permission`),
  FOREIGN KEY (`role_id`) REFERENCES `roles`(`id`)
);

-- "Marie is ACCOUNTANT in company 1 and CLERK in company 2."
CREATE TABLE `user_company_roles` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NULL,

  PRIMARY KEY (`user_id`, `company_id`, `role_id`),
  KEY `ix_user_company_roles_role_id` (`role_id`),
  FOREIGN KEY (`user_id`, `company_id`) REFERENCES `user_companies`(`user_id`, `company_id`),
  FOREIGN KEY (`role_id`) REFERENCES `roles`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`)
);

-- One permission explicitly granted to, or taken away from, one user in one
-- company. DENY beats everything, including an all_permissions role.
CREATE TABLE `user_permission_overrides` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `permission` VARCHAR(150) NOT NULL,
  `effect` VARCHAR(5) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NULL,

  PRIMARY KEY (`user_id`, `company_id`, `permission`),
  CONSTRAINT `ck_user_permission_overrides_effect` CHECK (`effect` IN ('GRANT', 'DENY')),
  FOREIGN KEY (`user_id`, `company_id`) REFERENCES `user_companies`(`user_id`, `company_id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`)
);

-- Assigned to nobody: bootstrap the first administrator with
--   java -jar stempy-erp-server.jar assign-role USER_NO COMPANY_CODE ADMINISTRATOR
INSERT INTO `roles` (`code`, `name`, `description`, `all_permissions`)
VALUES ('ADMINISTRATOR', 'Administrator', 'Every permission, including those added by future releases', TRUE);

-- ----------------------------------------------------
-- Business codes are unique per company, not install-wide
-- ----------------------------------------------------

ALTER TABLE `product_categories`
  DROP INDEX `code`,
  ADD UNIQUE KEY `uq_product_categories_company_code` (`company_id`, `code`);

ALTER TABLE `products`
  DROP INDEX `product_no`,
  ADD UNIQUE KEY `uq_products_company_product_no` (`company_id`, `product_no`);

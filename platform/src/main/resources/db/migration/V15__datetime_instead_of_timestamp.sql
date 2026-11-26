-- TIMESTAMP -> DATETIME, and the schema's character set stated.
--
-- TIMESTAMP stops at 2038-01-19 03:14:07 UTC: rows dated later (an audit entry, a schedule's
-- next run, a session's expiry) would fail to insert. DATETIME goes to 9999 and stores the
-- value as given, with no time zone conversion, so every value here is UTC by convention:
-- the application binds Instants as UTC (platform.db.Timestamps) and every connection forces
-- its session time zone to UTC (connectionTimeZone / forceConnectionTimeZoneToSession in
-- db.properties), which is also what DEFAULT CURRENT_TIMESTAMP and NOW() then use.
--
-- The conversion reads each TIMESTAMP in the session time zone, set to UTC first, so stored
-- instants are kept exactly. No fractional seconds, like before (whole seconds, see ModuleHost).
--
-- From now on: DATETIME for every date-time column, never TIMESTAMP.

SET time_zone = '+00:00';

-- New tables and columns default to utf8mb4 whatever the server's configuration.
-- DatabaseMigrator refuses to start on a schema with a table or column in another character set.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE audit_log
  MODIFY occurred_at DATETIME NOT NULL;

ALTER TABLE companies
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE currencies
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE gl_accounts
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE gl_dimension_sets
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE gl_dimension_values
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE gl_dimensions
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE gl_fiscal_years
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE gl_journal_entries
  MODIFY posted_at DATETIME NOT NULL;

ALTER TABLE gl_periods
  MODIFY status_changed_at DATETIME NULL;

ALTER TABLE idempotency_keys
  MODIFY created_at DATETIME NOT NULL;

ALTER TABLE number_series
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE number_series_counters
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

ALTER TABLE outbox
  MODIFY available_at DATETIME NOT NULL,
  MODIFY locked_until DATETIME NULL,
  MODIFY created_at DATETIME NOT NULL,
  MODIFY processed_at DATETIME NULL;

ALTER TABLE product_categories
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE products
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE roles
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE scheduled_job_runs
  MODIFY scheduled_for DATETIME NOT NULL,
  MODIFY created_at DATETIME NOT NULL,
  MODIFY started_at DATETIME NULL,
  MODIFY finished_at DATETIME NULL;

ALTER TABLE scheduled_jobs
  MODIFY start_at DATETIME NULL,
  MODIFY end_at DATETIME NULL,
  MODIFY next_run_at DATETIME NULL,
  MODIFY last_run_at DATETIME NULL,
  MODIFY locked_until DATETIME NULL,
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE tax_categories
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE tax_groups
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE tax_jurisdictions
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE tax_rates
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE tax_rules
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE units_of_measure
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_companies
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_company_roles
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_credentials
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

ALTER TABLE user_permission_overrides
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_sessions
  MODIFY created_at DATETIME NOT NULL,
  MODIFY last_seen_at DATETIME NOT NULL,
  MODIFY expires_at DATETIME NOT NULL,
  MODIFY revoked_at DATETIME NULL;

ALTER TABLE users
  MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  MODIFY deleted_at DATETIME NULL;

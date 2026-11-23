-- Scheduler (platform.scheduler, docs/ARCHITECTURE.md §3).
--
-- Code defines WHAT a job does: a module registers it under a job_key.
-- This table defines WHEN it runs, for which company, with which parameters.
-- It is read on every poll, so edits take effect without a restart. The
-- scheduler recomputes next_run_at whenever the timing columns change (it
-- compares schedule_fingerprint), so editing a row with plain SQL is safe.
--
-- The SYSTEM user that scheduled runs act as is not created here but by the
-- server on startup (users module, SystemActor), so a fresh database keeps
-- users.id = 1 free for hand-made or test users.
--
-- All timestamps are UTC, written by the server.

CREATE TABLE `scheduled_jobs` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  -- NULL = install-wide system job; otherwise it runs in that company's context.
  `company_id` BIGINT UNSIGNED NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,

  `job_key` VARCHAR(100) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `description` VARCHAR(255) NULL,

  -- CRON: cron_expression evaluated in time_zone. INTERVAL: interval_value
  -- (ISO-8601, e.g. PT15M) counted from start_at, else from created_at.
  `schedule_type` ENUM('CRON', 'INTERVAL') NOT NULL,
  `cron_expression` VARCHAR(100) NULL,
  `interval_value` VARCHAR(30) NULL,
  `time_zone` VARCHAR(50) NOT NULL DEFAULT 'America/Toronto',
  `parameters` JSON NULL,
  `is_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  -- Optional active window: no fire before start_at or after end_at.
  `start_at` TIMESTAMP NULL DEFAULT NULL,
  `end_at` TIMESTAMP NULL DEFAULT NULL,

  `misfire_policy` ENUM('FIRE_ONCE', 'SKIP') NOT NULL DEFAULT 'FIRE_ONCE',
  -- A fire time found later than this is a misfire.
  `misfire_threshold_seconds` INT UNSIGNED NOT NULL DEFAULT 300,
  -- NULL = the job type's default timeout.
  `timeout_seconds` INT UNSIGNED NULL,
  -- Failed runs are retried after retry_backoff_seconds * 2^attempt (capped at 1 hour).
  `max_retries` INT UNSIGNED NOT NULL DEFAULT 0,
  `retry_backoff_seconds` INT UNSIGNED NOT NULL DEFAULT 60,

  -- Runtime state, written by the scheduler only.
  `next_run_at` TIMESTAMP NULL DEFAULT NULL,          -- NULL: disabled, finished, or invalid
  `schedule_fingerprint` CHAR(64) NULL,               -- SHA-256 of the timing columns next_run_at came from
  `retry_attempt` INT UNSIGNED NOT NULL DEFAULT 0,    -- consecutive failures of the current fire
  `last_run_at` TIMESTAMP NULL DEFAULT NULL,
  `last_status` VARCHAR(20) NULL,
  `locked_by` VARCHAR(100) NULL,                      -- the instance running it (lease)
  `locked_until` TIMESTAMP NULL DEFAULT NULL,         -- lease end, renewed while running

  -- updated_at has no ON UPDATE: the scheduler's own writes are not edits.
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT UNSIGNED NULL,
  `deleted_at` TIMESTAMP NULL DEFAULT NULL,

  -- Names are unique per company, and among system jobs (NULLs never collide in a UNIQUE key).
  `company_key` BIGINT UNSIGNED AS (COALESCE(`company_id`, 0)) STORED,
  `name_key` VARCHAR(100) AS (IF(`deleted_at` IS NULL, `name`, NULL)) STORED,
  UNIQUE KEY `uq_scheduled_jobs_company_name` (`company_key`, `name_key`),

  KEY `ix_scheduled_jobs_due` (`is_enabled`, `next_run_at`),
  KEY `ix_scheduled_jobs_job_key` (`job_key`),

  CONSTRAINT `chk_scheduled_jobs_cron` CHECK (`schedule_type` <> 'CRON' OR `cron_expression` IS NOT NULL),
  CONSTRAINT `chk_scheduled_jobs_interval` CHECK (`schedule_type` <> 'INTERVAL' OR `interval_value` IS NOT NULL),
  CONSTRAINT `chk_scheduled_jobs_window` CHECK (`start_at` IS NULL OR `end_at` IS NULL OR `end_at` > `start_at`),

  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users`(`id`),
  FOREIGN KEY (`updated_by`) REFERENCES `users`(`id`)
);

-- One row per attempt (and per skipped fire). Pruned by the scheduler.prune-runs job.
CREATE TABLE `scheduled_job_runs` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `schedule_id` BIGINT UNSIGNED NOT NULL,
  -- Copied from the schedule at the time of the run, so history survives edits.
  `job_key` VARCHAR(100) NOT NULL,
  `company_id` BIGINT UNSIGNED NULL,

  `trigger_type` ENUM('SCHEDULE', 'RETRY', 'MANUAL') NOT NULL,
  -- The user who started a MANUAL run (the job acts as them); NULL otherwise.
  `requested_by` BIGINT UNSIGNED NULL,
  -- MANUAL only: parameters used instead of the schedule's.
  `parameters_override` JSON NULL,
  `status` ENUM('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'SKIPPED', 'ABANDONED') NOT NULL,
  `attempt` INT UNSIGNED NOT NULL DEFAULT 1,
  -- Fire time for SCHEDULE / RETRY; request time for MANUAL.
  `scheduled_for` TIMESTAMP NOT NULL,
  -- "<scheduled_for epoch seconds>:<attempt>" for SCHEDULE / RETRY, NULL for MANUAL:
  -- the database itself refuses to run the same fire twice.
  `fire_key` VARCHAR(40) NULL,

  `created_at` TIMESTAMP NOT NULL,
  `started_at` TIMESTAMP NULL DEFAULT NULL,
  `finished_at` TIMESTAMP NULL DEFAULT NULL,
  `duration_ms` BIGINT UNSIGNED NULL,
  `instance_id` VARCHAR(100) NULL,

  `result_summary` VARCHAR(1000) NULL,
  `error_message` VARCHAR(1000) NULL,
  `error_detail` TEXT NULL,

  UNIQUE KEY `uq_scheduled_job_runs_fire` (`schedule_id`, `fire_key`),
  KEY `ix_scheduled_job_runs_schedule` (`schedule_id`, `id`),
  KEY `ix_scheduled_job_runs_status` (`status`),
  KEY `ix_scheduled_job_runs_finished_at` (`finished_at`),

  FOREIGN KEY (`schedule_id`) REFERENCES `scheduled_jobs`(`id`),
  FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`),
  FOREIGN KEY (`requested_by`) REFERENCES `users`(`id`)
);

-- Audit trail (platform.audit, docs/ARCHITECTURE.md §20).
--
-- One row per audited change, written in the SAME transaction as the change:
-- if the change rolls back, so does its entry; if the entry can't be written,
-- the change fails. Written by AuditLog only: generic writes through the
-- EntityChanged listener, hand-written security writes (roles, memberships,
-- passwords, schedules) explicitly.
--
-- Rows are never updated. They are deleted only by the audit.prune job once
-- older than audit.retention (default P7Y, at least P6Y: CRA and Revenu
-- Quebec expect records kept 6 years after the year they relate to).
--
-- No foreign keys: the history must outlive the rows and users it names.
-- All timestamps are UTC, written by the server.

CREATE TABLE `audit_log` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  -- The company the changed row belongs to; NULL for install-wide data
  -- (system schedules, a user's password, ...).
  `company_id` BIGINT UNSIGNED NULL,
  -- What changed: EntityDefinition.name() for generic writes ("product"),
  -- a stable name for the others ("schedule", "user").
  `entity` VARCHAR(100) NOT NULL,
  `entity_id` BIGINT UNSIGNED NOT NULL,
  -- CREATED, UPDATED, DELETED, or a specific action (ROLE_ASSIGNED,
  -- MEMBERSHIP_GRANTED, PASSWORD_CHANGED, ...).
  `action` VARCHAR(50) NOT NULL,
  -- Who: the acting user (users.id); NULL when nobody was (a CLI command).
  `user_id` BIGINT UNSIGNED NULL,
  -- RequestContext.requestId: ties the entry to the request's log lines, a
  -- scheduled run (job-run-N) or an outbox delivery.
  `request_id` VARCHAR(100) NULL,
  -- {"field": {"old": ..., "new": ...}, ...} with API field names; only the
  -- fields whose value changed. NULL when the action carries no values.
  `changes` JSON NULL,
  `occurred_at` TIMESTAMP NOT NULL,

  KEY `ix_audit_log_record` (`entity`, `entity_id`, `id`),
  KEY `ix_audit_log_occurred_at` (`occurred_at`)
);

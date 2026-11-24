-- Outbox (platform.events.outbox, docs/ARCHITECTURE.md §18).
--
-- Work that must happen after a business transaction but must never break it
-- (emails, webhooks, search indexing). EventBus.publish() writes one row per
-- (event, async subscription) in the publisher's transaction, so a row exists
-- if and only if the business change committed. The OutboxDispatcher of each
-- server instance claims PENDING rows (FOR UPDATE SKIP LOCKED), calls the
-- subscription's handler, and marks the row SUCCEEDED, retries it later, or
-- gives up with DEAD.
--
-- Delivery is at least once: a crash after the handler ran but before the row
-- was marked runs it again. No ordering between rows is guaranteed.
--
-- To retry a DEAD row by hand:
--   UPDATE outbox SET status = 'PENDING', attempt = 0, available_at = UTC_TIMESTAMP() WHERE id = ?;
--
-- All timestamps are UTC, written by the server.

CREATE TABLE `outbox` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  -- AsyncSubscription.key(): which handler this row is for.
  `subscription_key` VARCHAR(100) NOT NULL,
  -- Fully-qualified class of the event; payload is deserialized into it.
  -- Renaming or moving an event class strands its pending rows (they go DEAD).
  `event_type` VARCHAR(255) NOT NULL,
  `payload` JSON NOT NULL,
  -- DomainEvent.companyId(); the handler acts as SYSTEM in this company.
  `company_id` BIGINT UNSIGNED NOT NULL,

  `status` ENUM('PENDING', 'PROCESSING', 'SUCCEEDED', 'DEAD') NOT NULL DEFAULT 'PENDING',
  -- Attempts started so far (incremented when a dispatcher claims the row).
  `attempt` INT UNSIGNED NOT NULL DEFAULT 0,
  -- Not claimed before this time: the retry backoff.
  `available_at` TIMESTAMP NOT NULL,
  -- The lease while PROCESSING: which instance runs it, until when. An
  -- expired lease means that instance died; the row is retried.
  `locked_by` VARCHAR(100) NULL,
  `locked_until` TIMESTAMP NULL DEFAULT NULL,
  -- Message and stack trace of the last failure.
  `last_error` TEXT NULL,

  `created_at` TIMESTAMP NOT NULL,
  -- When it became SUCCEEDED or DEAD; the prune job uses it.
  `processed_at` TIMESTAMP NULL DEFAULT NULL,

  KEY `ix_outbox_claim` (`status`, `available_at`, `id`),
  KEY `ix_outbox_processed` (`status`, `processed_at`)
);

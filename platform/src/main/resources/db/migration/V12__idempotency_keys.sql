-- Idempotency keys (platform.idempotency, docs/ARCHITECTURE.md §22.1).
--
-- A client that creates a document sends an Idempotency-Key header; retrying
-- with the same key (after a timeout, say) replays the first result instead
-- of creating the document twice.
--
-- The row is written in the SAME transaction as the document: the key exists
-- if and only if the document does. A concurrent request with the same key
-- waits on the row's unique key until the first commits (then replays it) or
-- rolls back (then runs). There is no "in progress" state to get stuck in.
--
-- result: what to replay (for a journal entry, its id). request_hash: the
-- SHA-256 of the request body; the same key with another body is refused.
-- Rows are deleted by the idempotency.prune job after 7 days.
--
-- All timestamps are UTC, written by the server.

CREATE TABLE `idempotency_keys` (
  `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  `company_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  -- What the key is for, e.g. 'POST /gl/journal-entries': the same key may be
  -- used for two different operations.
  `scope` VARCHAR(100) NOT NULL,
  `idem_key` VARCHAR(100) NOT NULL,
  `request_hash` CHAR(64) NOT NULL,
  `result` VARCHAR(255) NULL,
  `created_at` TIMESTAMP NOT NULL,

  UNIQUE KEY `uq_idempotency_keys` (`company_id`, `user_id`, `scope`, `idem_key`),
  KEY `ix_idempotency_keys_created` (`created_at`)
);

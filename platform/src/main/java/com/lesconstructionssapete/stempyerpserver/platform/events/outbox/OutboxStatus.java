package com.lesconstructionssapete.stempyerpserver.platform.events.outbox;

/** outbox.status, stored by name. */
public enum OutboxStatus {
  /** Waiting for available_at, then for a dispatcher to claim it. */
  PENDING,
  /** Claimed: a dispatcher is running its handler (see locked_by / locked_until). */
  PROCESSING,
  /** The handler returned normally. Pruned after outbox.retention. */
  SUCCEEDED,
  /** Every attempt failed, or the event could not be read back. Pruned after outbox.deadRetention. */
  DEAD
}

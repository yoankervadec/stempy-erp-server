package com.lesconstructionssapete.stempyerpserver.platform.security;

/**
 * The user that work nobody asked for in person is done as (scheduled jobs,
 * outbox deliveries): its id goes in RequestContext.userId and in created_by /
 * updated_by. Published by the users module, which owns the users table.
 *
 * The SYSTEM user cannot log in: it is disabled and has no credentials.
 */
public interface SystemActor {

  /** The users.user_no of the system user. */
  String USER_NO = "SYSTEM";

  /**
   * The system user's users.id. The first call creates the row if it is
   * missing; later calls return the cached id. Thread-safe.
   */
  long userId();
}

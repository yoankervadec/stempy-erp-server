package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

/**
 * What to do with a fire time found late (the server was down, or the
 * previous run was still going), stored in scheduled_jobs.misfire_policy.
 */
public enum MisfirePolicy {
  /** Run once now; any other fire times missed meanwhile are not replayed. */
  FIRE_ONCE,
  /** Do not run; record a SKIPPED run and wait for the next fire time. */
  SKIP
}

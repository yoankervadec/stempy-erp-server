package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

/** Why a run happened, stored in scheduled_job_runs.trigger_type. */
public enum TriggerType {
  /** A regular fire time of the schedule. */
  SCHEDULE,
  /** A new attempt after a failed SCHEDULE or RETRY run. */
  RETRY,
  /** Started by a user ("run now"); scheduled_job_runs.requested_by says who. */
  MANUAL
}

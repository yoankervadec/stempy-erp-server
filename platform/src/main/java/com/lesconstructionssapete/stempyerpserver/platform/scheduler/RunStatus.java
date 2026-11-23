package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

/** State of one run in scheduled_job_runs.status. */
public enum RunStatus {
  /** A manual run waiting for its schedule to be free. */
  QUEUED,
  RUNNING,
  SUCCEEDED,
  /** The job threw. */
  FAILED,
  /** The job ran past its timeout and was interrupted. */
  TIMED_OUT,
  /** A missed fire time dropped by the SKIP misfire policy; the job did not run. */
  SKIPPED,
  /** The instance running it stopped renewing its lease (crash, lost database). */
  ABANDONED;

  /** Whether the run is over; FAILED, TIMED_OUT and ABANDONED count as failures for retries. */
  public boolean isFinished() {
    return this != QUEUED && this != RUNNING;
  }

  public boolean isFailure() {
    return this == FAILED || this == TIMED_OUT || this == ABANDONED;
  }
}

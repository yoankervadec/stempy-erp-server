package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;

/** Records how runs ended, and decides retries. Shared by JobRunner and the reaper. */
final class RunRecorder {

  private static final Logger LOGGER = LoggerFactory.getLogger(RunRecorder.class);

  /** The outcome of one run, as the job left it. */
  record Outcome(RunStatus status, long durationMs, String resultSummary, String errorMessage, String errorDetail) {
  }

  private final UnitOfWorkFactory uowFactory;
  private final ScheduleRepository schedules;
  private final RunRepository runs;
  private final String instanceId;

  RunRecorder(UnitOfWorkFactory uowFactory, ScheduleRepository schedules, RunRepository runs, String instanceId) {
    this.uowFactory = uowFactory;
    this.schedules = schedules;
    this.runs = runs;
    this.instanceId = instanceId;
  }

  /**
   * In one transaction: finishes the run row, releases our lease, and for a
   * scheduled run applies the retry rules. If the lease was no longer ours
   * (another instance reaped it) only this log keeps the outcome.
   */
  void finish(Claim claim, Outcome o) {
    uowFactory.run(uow -> {
      Instant now = schedules.now(uow);
      boolean recorded = runs.finish(uow, claim.runId(), o.status(), now, o.durationMs(), o.resultSummary(),
          o.errorMessage(), o.errorDetail());
      boolean released = schedules.release(uow, claim.schedule().id(), instanceId, now, o.status());
      if (!recorded || !released) {
        LOGGER.warn("Run {} of schedule {} ended {} after losing its lease; the outcome is not recorded",
            claim.runId(), claim.schedule().id(), o.status());
        return;
      }
      if (claim.trigger() != TriggerType.MANUAL) {
        applyRetryRules(uow, claim.schedule().id(), o.status(), now);
      }
    });
  }

  /**
   * After a scheduled run ended: a success clears the retry count; a failure
   * schedules a retry after the backoff, unless retries are used up or the
   * next regular fire comes first (it supersedes the retry).
   */
  void applyRetryRules(UnitOfWork uow, long scheduleId, RunStatus status, Instant now) {
    ScheduleRow row = schedules.findById(uow, scheduleId).orElse(null);
    if (row == null) {
      return; // deleted meanwhile
    }
    if (!status.isFailure() || row.retryAttempt() >= row.maxRetries()) {
      if (status.isFailure() && row.maxRetries() > 0) {
        LOGGER.warn("Schedule {} ({}) failed after {} retr{}; waiting for its next fire time", row.id(), row.name(),
            row.retryAttempt(), row.retryAttempt() == 1 ? "y" : "ies");
      }
      if (row.retryAttempt() != 0) {
        schedules.setRetry(uow, scheduleId, 0, null);
      }
      return;
    }
    Instant retryAt = now.plus(RetryPolicy.backoff(row.retryAttempt(), row.retryBackoffSeconds()));
    if (row.nextRunAt() != null && !retryAt.isBefore(row.nextRunAt())) {
      schedules.setRetry(uow, scheduleId, 0, null); // the next regular fire comes first
      return;
    }
    int attempt = row.retryAttempt() + 1;
    schedules.setRetry(uow, scheduleId, attempt, retryAt);
    LOGGER.info("Schedule {} ({}) will retry at {} (retry {} of {})", row.id(), row.name(), retryAt, attempt,
        row.maxRetries());
  }
}

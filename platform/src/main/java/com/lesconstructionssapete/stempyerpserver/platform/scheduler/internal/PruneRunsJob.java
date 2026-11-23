package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;

/**
 * The scheduler's own housekeeping: deletes finished runs older than the
 * retention. Registered by ModuleHost, so it exists whatever modules are
 * installed.
 *
 * Parameters (optional): {@code {"retention": "P30D"}} overrides
 * scheduler.runRetention for this schedule or manual run.
 */
public final class PruneRunsJob {

  public static final String KEY = "scheduler.prune-runs";
  static final int BATCH = 1000;
  static final Duration MIN_RETENTION = Duration.ofDays(1);

  private PruneRunsJob() {
  }

  /**
   * @param defaultRetention scheduler.runRetention, read at each run (the
   *                         settings are only known once the scheduler starts)
   */
  public static JobType type(UnitOfWorkFactory uowFactory, Supplier<Duration> defaultRetention) {
    var schedules = new ScheduleRepository();
    var runs = new RunRepository();
    return JobType.of(KEY, "Deletes finished scheduler runs older than the retention "
        + "(scheduler.runRetention, or the 'retention' parameter)", ctx -> {
          Duration retention = retention(ctx.parameters(), defaultRetention.get());
          Instant cutoff = uowFactory.executeReadOnly(schedules::now).minus(retention);
          long total = 0;
          int deleted;
          do {
            ctx.throwIfCancelled();
            // One short transaction per batch: no long lock on the history table.
            deleted = uowFactory.execute(uow -> runs.deleteFinishedBefore(uow, cutoff, BATCH));
            total += deleted;
          } while (deleted == BATCH);
          ctx.setResultSummary(total + " run(s) finished before " + cutoff + " deleted (retention " + retention + ")");
        })
        .withParameters(PruneRunsJob::validate)
        .withTimeout(Duration.ofMinutes(30))
        .withScope(JobType.Scope.SYSTEM)
        .withDefaultSchedule(DefaultSchedule.cron("Prune scheduler run history", "30 3 * * *", "America/Toronto"));
  }

  private static void validate(JsonNode parameters) {
    retention(parameters, MIN_RETENTION);
  }

  private static Duration retention(JsonNode parameters, Duration fallback) {
    JsonNode value = parameters.get("retention");
    if (value == null || value.isNull()) {
      return fallback;
    }
    try {
      Duration retention = Duration.parse(value.asText());
      if (retention.compareTo(MIN_RETENTION) >= 0) {
        return retention;
      }
    } catch (DateTimeException e) {
      // reported below
    }
    throw ValidationFailedException.field("parameters.retention", "INVALID_FORMAT",
        "must be an ISO-8601 duration of at least P1D, e.g. P30D");
  }
}

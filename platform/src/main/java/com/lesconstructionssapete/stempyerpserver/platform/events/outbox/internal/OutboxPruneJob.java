package com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxSettings;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobContext;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;

/**
 * The outbox's housekeeping, run by the scheduler: deletes SUCCEEDED rows
 * older than outbox.retention and DEAD rows older than outbox.deadRetention.
 * Registered by ModuleHost, so it exists whatever modules are installed.
 * PENDING and PROCESSING rows are never touched.
 */
public final class OutboxPruneJob {

  public static final String KEY = "outbox.prune";
  static final int BATCH = 1000;

  private OutboxPruneJob() {
  }

  /** @param settings the outbox settings, read at each run */
  public static JobType type(UnitOfWorkFactory uowFactory, Supplier<OutboxSettings> settings) {
    var outbox = new OutboxRepository();
    return JobType.of(KEY, "Deletes delivered outbox messages older than outbox.retention and dead ones older "
        + "than outbox.deadRetention", ctx -> {
          OutboxSettings s = settings.get();
          Instant now = uowFactory.executeReadOnly(outbox::now);
          long succeeded = prune(ctx, uowFactory, outbox, OutboxStatus.SUCCEEDED, now.minus(s.retention()));
          long dead = prune(ctx, uowFactory, outbox, OutboxStatus.DEAD, now.minus(s.deadRetention()));
          ctx.setResultSummary(succeeded + " delivered (retention " + s.retention() + ") and " + dead
              + " dead (retention " + s.deadRetention() + ") message(s) deleted");
        })
        .withTimeout(Duration.ofMinutes(30))
        .withScope(JobType.Scope.SYSTEM)
        .withDefaultSchedule(DefaultSchedule.cron("Prune outbox", "45 3 * * *", "America/Toronto"));
  }

  private static long prune(JobContext ctx, UnitOfWorkFactory uowFactory, OutboxRepository outbox,
      OutboxStatus status, Instant cutoff) {
    long total = 0;
    int deleted;
    do {
      ctx.throwIfCancelled();
      // One short transaction per batch: no long lock on a table every write path inserts into.
      deleted = uowFactory.execute(uow -> outbox.deleteProcessedBefore(uow, status, cutoff, BATCH));
      total += deleted;
    } while (deleted == BATCH);
    return total;
  }
}

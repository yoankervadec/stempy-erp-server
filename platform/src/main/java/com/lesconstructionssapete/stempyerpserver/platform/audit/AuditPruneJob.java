package com.lesconstructionssapete.stempyerpserver.platform.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;

/**
 * Deletes audit entries older than the retention (audit.retention, default
 * P7Y), in batches. Registered by ModuleHost. Never goes below
 * AuditSettings.MIN_RETENTION, even when asked to.
 *
 * Parameters (optional): {@code {"retention": "P10Y"}} overrides
 * audit.retention for this schedule or manual run.
 */
public final class AuditPruneJob {

  public static final String KEY = "audit.prune";
  static final int BATCH = 1000;

  private AuditPruneJob() {
  }

  /** @param settings read at each run (the settings are only known once the server starts) */
  public static JobType type(UnitOfWorkFactory uowFactory, AuditLog audit, Supplier<AuditSettings> settings,
      Clock clock) {
    return JobType.of(KEY, "Deletes audit entries older than the retention (audit.retention, or the "
        + "'retention' parameter; never less than " + AuditSettings.MIN_RETENTION + ")", ctx -> {
          Period retention = retention(ctx.parameters(), settings.get().retention());
          Instant cutoff = clock.instant().atZone(ZoneOffset.UTC).minus(retention).toInstant();
          long total = 0;
          int deleted;
          do {
            ctx.throwIfCancelled();
            // One short transaction per batch: no long lock on a table every write inserts into.
            deleted = uowFactory.execute(uow -> audit.deleteBefore(uow, cutoff, BATCH));
            total += deleted;
          } while (deleted == BATCH);
          ctx.setResultSummary(total + " audit entr" + (total == 1 ? "y" : "ies") + " from before " + cutoff
              + " deleted (retention " + retention + ")");
        })
        .withParameters(p -> retention(p, AuditSettings.MIN_RETENTION))
        .withTimeout(Duration.ofMinutes(30))
        .withScope(JobType.Scope.SYSTEM)
        .withDefaultSchedule(DefaultSchedule.cron("Prune audit log", "0 4 * * *", "America/Toronto"));
  }

  private static Period retention(JsonNode parameters, Period fallback) {
    JsonNode value = parameters.get("retention");
    if (value == null || value.isNull()) {
      return fallback;
    }
    try {
      Period retention = AuditSettings.parse(value.asText());
      if (AuditSettings.atLeastMinimum(retention)) {
        return retention;
      }
    } catch (IllegalArgumentException e) {
      // reported below
    }
    throw ValidationFailedException.field("parameters.retention", "INVALID_FORMAT",
        "must be an ISO-8601 period of at least " + AuditSettings.MIN_RETENTION + ", e.g. P7Y");
  }
}

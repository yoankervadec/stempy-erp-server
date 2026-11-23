package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.time.Instant;

/** A scheduled_jobs row as the admin API sees it. {@code companyId} null: an install-wide (system) schedule. */
public record StoredSchedule(
    long id,
    int version,
    Long companyId,
    ScheduleFields fields,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    int retryAttempt,
    String lockedBy,
    Instant lockedUntil,
    Instant createdAt,
    Long createdBy,
    Instant updatedAt,
    Long updatedBy) {

  public boolean isSystem() {
    return companyId == null;
  }
}

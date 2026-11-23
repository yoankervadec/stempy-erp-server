package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

/**
 * One scheduled_jobs row, as stored (nothing validated: the row may have been
 * edited with plain SQL). Use {@link Schedules} to interpret it.
 */
public record ScheduleRow(
    long id,
    Long companyId,
    String jobKey,
    String name,
    ScheduleType scheduleType,
    String cronExpression,
    String intervalValue,
    String timeZone,
    String parameters,
    boolean enabled,
    Instant startAt,
    Instant endAt,
    MisfirePolicy misfirePolicy,
    int misfireThresholdSeconds,
    Integer timeoutSeconds,
    int maxRetries,
    int retryBackoffSeconds,
    Instant nextRunAt,
    String fingerprint,
    int retryAttempt,
    Instant lastRunAt,
    String lastStatus,
    String lockedBy,
    Instant lockedUntil,
    Instant createdAt) {
}

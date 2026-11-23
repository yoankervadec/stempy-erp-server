package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleScope;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

/**
 * A schedule as the API returns it.
 *
 * @param jobRegistered false: no job type with this key on this server (an
 *                      older or newer version may have it); it won't run here
 * @param runningOn     the instance running it right now, or null
 */
public record ScheduleView(
    long id,
    int version,
    ScheduleScope scope,
    String jobKey,
    boolean jobRegistered,
    String name,
    String description,
    ScheduleType scheduleType,
    String cronExpression,
    String interval,
    String timeZone,
    JsonNode parameters,
    boolean isEnabled,
    Instant startAt,
    Instant endAt,
    MisfirePolicy misfirePolicy,
    int misfireThresholdSeconds,
    Integer timeoutSeconds,
    int maxRetries,
    int retryBackoffSeconds,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    int retryAttempt,
    String runningOn,
    Instant createdAt,
    Long createdBy,
    Instant updatedAt,
    Long updatedBy) {
}

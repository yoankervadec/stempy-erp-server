package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

/**
 * The columns of a schedule a user may set. {@code parameters} is JSON text
 * (an object) or null.
 */
public record ScheduleFields(
    String name,
    String description,
    String jobKey,
    ScheduleType scheduleType,
    String cronExpression,
    String interval,
    String timeZone,
    String parameters,
    boolean enabled,
    Instant startAt,
    Instant endAt,
    MisfirePolicy misfirePolicy,
    int misfireThresholdSeconds,
    Integer timeoutSeconds,
    int maxRetries,
    int retryBackoffSeconds) {

  public static final String DEFAULT_TIME_ZONE = "America/Toronto";

  /** What a new schedule starts from before the request body is applied. */
  static ScheduleFields defaults() {
    return new ScheduleFields(null, null, null, null, null, null, DEFAULT_TIME_ZONE, null, true, null, null,
        MisfirePolicy.FIRE_ONCE, 300, null, 0, 60);
  }
}

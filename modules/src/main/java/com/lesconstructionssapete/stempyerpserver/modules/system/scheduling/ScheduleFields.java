package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;
import com.lesconstructionssapete.stempyerpserver.platform.write.FieldChanges;

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

  /**
   * The changes between two versions, for the audit log, by API field name
   * ({@code isEnabled}, as clients send it, not the component's
   * {@code enabled}).
   *
   * @param before null for a creation
   */
  public static Map<String, FieldChange> changes(ScheduleFields before, ScheduleFields after) {
    Map<String, FieldChange> out = new LinkedHashMap<>();
    FieldChanges.records(before, after).forEach((field, change) ->
        out.put(field.equals("enabled") ? "isEnabled" : field, change));
    return out;
  }

  /** What a new schedule starts from before the request body is applied. */
  static ScheduleFields defaults() {
    return new ScheduleFields(null, null, null, null, null, null, DEFAULT_TIME_ZONE, null, true, null, null,
        MisfirePolicy.FIRE_ONCE, 300, null, 0, 60);
  }
}

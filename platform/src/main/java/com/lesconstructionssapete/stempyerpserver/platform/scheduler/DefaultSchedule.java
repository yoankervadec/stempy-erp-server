package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import java.time.Instant;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleSpec;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

/**
 * A system schedule a job type brings with it. On startup, if no row (deleted
 * or not) exists for the job key, this one is inserted; an existing row is
 * never changed, so edits made at runtime are kept, and deleting the row
 * turns the job off for good.
 */
public record DefaultSchedule(String name, ScheduleType type, String expression, String timeZone) {

  public DefaultSchedule {
    Objects.requireNonNull(name, "name");
    // fail fast at startup on a bad expression
    ScheduleSpec.of(type, type == ScheduleType.CRON ? expression : null,
        type == ScheduleType.INTERVAL ? expression : null, timeZone, Instant.EPOCH);
  }

  /** e.g. cron("Purge sessions", "0 * * * *", "America/Toronto") */
  public static DefaultSchedule cron(String name, String cronExpression, String timeZone) {
    return new DefaultSchedule(name, ScheduleType.CRON, cronExpression, timeZone);
  }

  /** e.g. interval("Poll bank feed", "PT15M"); the time zone is irrelevant to intervals. */
  public static DefaultSchedule interval(String name, String isoDuration) {
    return new DefaultSchedule(name, ScheduleType.INTERVAL, isoDuration, "UTC");
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule;

/** How a schedule row says when to run; stored as-is in scheduled_jobs.schedule_type. */
public enum ScheduleType {
  /** A cron expression evaluated in the row's time zone. */
  CRON,
  /** A fixed-rate ISO-8601 duration counted from an anchor instant. */
  INTERVAL
}

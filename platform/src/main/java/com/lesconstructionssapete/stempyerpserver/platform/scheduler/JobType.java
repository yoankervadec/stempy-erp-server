package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A kind of job a module can run, registered in ERPModule.registerJobs under
 * a stable key. Schedules (rows in scheduled_jobs) refer to it by that key.
 *
 * <pre>
 * scheduler.register(JobType.of("users.purge-expired-sessions", "Deletes sessions that can no longer be used",
 *         Job.transactional(uowFactory, (uow, ctx) -&gt; ...))
 *     .withTimeout(Duration.ofMinutes(5))
 *     .withDefaultSchedule(DefaultSchedule.cron("Purge expired sessions", "0 * * * *", "UTC")));
 * </pre>
 *
 * @param timeout         null: the scheduler's default; a schedule's
 *                        timeout_seconds wins over both
 * @param defaultSchedule null: none
 */
public record JobType(String key, String description, Job job, Duration timeout, ParameterValidator parameters,
    DefaultSchedule defaultSchedule) {

  /** "module.verb-noun": lower case, dots and dashes, at most 100 characters. */
  private static final Pattern KEY = Pattern.compile("[a-z0-9]+([.-][a-z0-9]+)*");

  public JobType {
    Objects.requireNonNull(key, "key");
    if (key.length() > 100 || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid job key '" + key + "': use lower case, digits, dots and dashes");
    }
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(parameters, "parameters");
    if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
  }

  public static JobType of(String key, String description, Job job) {
    return new JobType(key, description, job, null, ParameterValidator.ANY, null);
  }

  public JobType withTimeout(Duration timeout) {
    return new JobType(key, description, job, timeout, parameters, defaultSchedule);
  }

  public JobType withParameters(ParameterValidator validator) {
    return new JobType(key, description, job, timeout, validator, defaultSchedule);
  }

  public JobType withDefaultSchedule(DefaultSchedule schedule) {
    return new JobType(key, description, job, timeout, parameters, schedule);
  }
}

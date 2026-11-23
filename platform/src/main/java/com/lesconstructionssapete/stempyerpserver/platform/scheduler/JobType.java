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
 * Scheduled runs act as the SYSTEM user with every permission, so who may
 * schedule a job matters: {@code permission} (if set) is required, on top of
 * the scheduler.* permissions, to create, change or run a schedule of this
 * job, and {@code scope} says whether schedules may be install-wide, per
 * company, or both.
 *
 * @param timeout         null: the scheduler's default; a schedule's
 *                        timeout_seconds wins over both
 * @param defaultSchedule null: none (a default schedule is install-wide, so
 *                        the scope must allow SYSTEM)
 * @param scope           where schedules of this job may live
 * @param permission      null: none beyond the scheduler.* permissions; must
 *                        be declared by a module (checked at startup)
 */
public record JobType(String key, String description, Job job, Duration timeout, ParameterValidator parameters,
    DefaultSchedule defaultSchedule, Scope scope, String permission) {

  /** Where schedules of a job type may live. */
  public enum Scope {
    /** Install-wide only (company_id NULL): housekeeping that spans companies. */
    SYSTEM,
    /** One schedule per company that wants it; the job works on that company. */
    COMPANY,
    ANY;

    public boolean allowsSystem() {
      return this != COMPANY;
    }

    public boolean allowsCompany() {
      return this != SYSTEM;
    }
  }

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
    Objects.requireNonNull(scope, "scope");
    if (defaultSchedule != null && !scope.allowsSystem()) {
      throw new IllegalArgumentException("Job type " + key + " is COMPANY-scoped and cannot have a default schedule");
    }
    if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
  }

  public static JobType of(String key, String description, Job job) {
    return new JobType(key, description, job, null, ParameterValidator.ANY, null, Scope.ANY, null);
  }

  public JobType withTimeout(Duration timeout) {
    return new JobType(key, description, job, timeout, parameters, defaultSchedule, scope, permission);
  }

  public JobType withParameters(ParameterValidator validator) {
    return new JobType(key, description, job, timeout, validator, defaultSchedule, scope, permission);
  }

  public JobType withDefaultSchedule(DefaultSchedule schedule) {
    return new JobType(key, description, job, timeout, parameters, schedule, scope, permission);
  }

  public JobType withScope(Scope newScope) {
    return new JobType(key, description, job, timeout, parameters, defaultSchedule, newScope, permission);
  }

  /** A permission the caller must hold to schedule or run this job (e.g. "gl.journal.post"). */
  public JobType withPermission(String requiredPermission) {
    return new JobType(key, description, job, timeout, parameters, defaultSchedule, scope,
        Objects.requireNonNull(requiredPermission, "requiredPermission"));
  }

  /** Whether a schedule for this company (null: install-wide) is allowed. */
  public boolean allows(Long companyId) {
    return companyId == null ? scope.allowsSystem() : scope.allowsCompany();
  }
}

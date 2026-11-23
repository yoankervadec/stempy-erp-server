package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

/**
 * scheduled_jobs and scheduled_job_runs, for the admin API. Raw SQL, no
 * rules. The scheduler itself has its own queries (platform.scheduler); the
 * two only share the tables.
 *
 * Scope arguments: {@code companyId} selects that company's rows (null:
 * none), {@code system} adds the install-wide rows (company_id NULL).
 * Soft-deleted schedules are invisible.
 */
public class ScheduleAdminRepository {

  private static final String SCHEDULE_COLUMNS = """
      id, version, company_id, job_key, name, description, schedule_type, cron_expression, interval_value,
      time_zone, parameters, is_enabled, start_at, end_at, misfire_policy, misfire_threshold_seconds,
      timeout_seconds, max_retries, retry_backoff_seconds, next_run_at, last_run_at, last_status, retry_attempt,
      locked_by, locked_until, created_at, created_by, updated_at, updated_by""";

  private static final String RUN_COLUMNS = """
      id, schedule_id, job_key, company_id, trigger_type, requested_by, parameters_override, status, attempt,
      scheduled_for, created_at, started_at, finished_at, duration_ms, instance_id, result_summary,
      error_message, error_detail""";

  // ---- schedules ----

  public List<StoredSchedule> listSchedules(UnitOfWork uow, Long companyId, boolean system, int limit, int offset) {
    List<Object> params = new ArrayList<>();
    String scope = scope(companyId, system, params);
    if (scope == null) {
      return List.of();
    }
    params.add(limit);
    params.add(offset);
    return Jdbc.queryList(uow, "SELECT " + SCHEDULE_COLUMNS + " FROM scheduled_jobs WHERE deleted_at IS NULL AND "
        + scope + " ORDER BY name, id LIMIT ? OFFSET ?", ScheduleAdminRepository::schedule, params.toArray());
  }

  public Optional<StoredSchedule> findSchedule(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + SCHEDULE_COLUMNS + " FROM scheduled_jobs WHERE id = ? AND deleted_at IS NULL",
        ScheduleAdminRepository::schedule, id);
  }

  /** Whether another live schedule in the same scope (company, or system) has this name. */
  public boolean nameTaken(UnitOfWork uow, Long companyId, String name, long exceptId) {
    return Jdbc.queryFirst(uow, """
        SELECT 1 FROM scheduled_jobs
        WHERE company_key = COALESCE(?, 0) AND name = ? AND deleted_at IS NULL AND id <> ?
        LIMIT 1""", rs -> true, companyId, name, exceptId).isPresent();
  }

  public long insertSchedule(UnitOfWork uow, Long companyId, ScheduleFields f, long userId, Instant now) {
    return Jdbc.insert(uow, """
        INSERT INTO scheduled_jobs (company_id, job_key, name, description, schedule_type, cron_expression,
          interval_value, time_zone, parameters, is_enabled, start_at, end_at, misfire_policy,
          misfire_threshold_seconds, timeout_seconds, max_retries, retry_backoff_seconds,
          created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        companyId, f.jobKey(), f.name(), f.description(), f.scheduleType(), f.cronExpression(), f.interval(),
        f.timeZone(), f.parameters(), f.enabled(), f.startAt(), f.endAt(), f.misfirePolicy(),
        f.misfireThresholdSeconds(), f.timeoutSeconds(), f.maxRetries(), f.retryBackoffSeconds(),
        now, userId, now, userId);
  }

  /**
   * Replaces the editable columns if the row is still at {@code version}.
   * The scheduler notices timing changes by itself (fingerprint) and
   * recomputes next_run_at. Returns the rows changed (0: stale version or gone).
   */
  public int updateSchedule(UnitOfWork uow, long id, int version, ScheduleFields f, long userId, Instant now) {
    return Jdbc.update(uow, """
        UPDATE scheduled_jobs SET
          job_key = ?, name = ?, description = ?, schedule_type = ?, cron_expression = ?, interval_value = ?,
          time_zone = ?, parameters = ?, is_enabled = ?, start_at = ?, end_at = ?, misfire_policy = ?,
          misfire_threshold_seconds = ?, timeout_seconds = ?, max_retries = ?, retry_backoff_seconds = ?,
          version = version + 1, updated_at = ?, updated_by = ?
        WHERE id = ? AND version = ? AND deleted_at IS NULL""",
        f.jobKey(), f.name(), f.description(), f.scheduleType(), f.cronExpression(), f.interval(), f.timeZone(),
        f.parameters(), f.enabled(), f.startAt(), f.endAt(), f.misfirePolicy(), f.misfireThresholdSeconds(),
        f.timeoutSeconds(), f.maxRetries(), f.retryBackoffSeconds(), now, userId, id, version);
  }

  /** Soft delete if still at {@code version}; it stops firing at once. Returns the rows changed. */
  public int softDeleteSchedule(UnitOfWork uow, long id, int version, long userId, Instant now) {
    return Jdbc.update(uow, """
        UPDATE scheduled_jobs SET deleted_at = ?, next_run_at = NULL, version = version + 1,
          updated_at = ?, updated_by = ?
        WHERE id = ? AND version = ? AND deleted_at IS NULL""", now, now, userId, id, version);
  }

  // ---- runs ----

  public boolean hasQueuedRun(UnitOfWork uow, long scheduleId) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM scheduled_job_runs WHERE schedule_id = ? AND status = 'QUEUED' LIMIT 1",
        rs -> true, scheduleId).isPresent();
  }

  /** A QUEUED manual run; the scheduler starts it once the schedule is free. */
  public long insertManualRun(UnitOfWork uow, StoredSchedule s, long userId, String parameters, Instant now) {
    return Jdbc.insert(uow, """
        INSERT INTO scheduled_job_runs (schedule_id, job_key, company_id, trigger_type, requested_by,
          parameters_override, status, attempt, scheduled_for, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""",
        s.id(), s.fields().jobKey(), s.companyId(), TriggerType.MANUAL, userId, parameters, RunStatus.QUEUED,
        now, now);
  }

  /** Queued runs of a deleted schedule would wait forever: they end SKIPPED. */
  public int skipQueuedRuns(UnitOfWork uow, long scheduleId, Instant now, String reason) {
    return Jdbc.update(uow, """
        UPDATE scheduled_job_runs SET status = 'SKIPPED', finished_at = ?, error_message = ?
        WHERE schedule_id = ? AND status = 'QUEUED'""", now, reason, scheduleId);
  }

  /** Newest first, optionally for one schedule and one status. */
  public List<StoredRun> listRuns(UnitOfWork uow, Long companyId, boolean system, Long scheduleId, RunStatus status,
      int limit, int offset) {
    List<Object> params = new ArrayList<>();
    String scope = scope(companyId, system, params);
    if (scope == null) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS + " FROM scheduled_job_runs WHERE " + scope);
    if (scheduleId != null) {
      sql.append(" AND schedule_id = ?");
      params.add(scheduleId);
    }
    if (status != null) {
      sql.append(" AND status = ?");
      params.add(status);
    }
    sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
    params.add(limit);
    params.add(offset);
    return Jdbc.queryList(uow, sql.toString(), ScheduleAdminRepository::run, params.toArray());
  }

  public Optional<StoredRun> findRun(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + RUN_COLUMNS + " FROM scheduled_job_runs WHERE id = ?",
        ScheduleAdminRepository::run, id);
  }

  // ---- mapping ----

  /** "(company_id = ? OR company_id IS NULL)", or null when the caller may see nothing. */
  private static String scope(Long companyId, boolean system, List<Object> params) {
    List<String> parts = new ArrayList<>();
    if (companyId != null) {
      parts.add("company_id = ?");
      params.add(companyId);
    }
    if (system) {
      parts.add("company_id IS NULL");
    }
    return parts.isEmpty() ? null : "(" + String.join(" OR ", parts) + ")";
  }

  private static StoredSchedule schedule(ResultSet rs) throws SQLException {
    var fields = new ScheduleFields(
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("job_key"),
        ScheduleType.valueOf(rs.getString("schedule_type")),
        rs.getString("cron_expression"),
        rs.getString("interval_value"),
        rs.getString("time_zone"),
        rs.getString("parameters"),
        rs.getBoolean("is_enabled"),
        Timestamps.read(rs, "start_at"),
        Timestamps.read(rs, "end_at"),
        MisfirePolicy.valueOf(rs.getString("misfire_policy")),
        rs.getInt("misfire_threshold_seconds"),
        rs.getObject("timeout_seconds", Integer.class),
        rs.getInt("max_retries"),
        rs.getInt("retry_backoff_seconds"));
    return new StoredSchedule(
        rs.getLong("id"),
        rs.getInt("version"),
        rs.getObject("company_id", Long.class),
        fields,
        Timestamps.read(rs, "next_run_at"),
        Timestamps.read(rs, "last_run_at"),
        rs.getString("last_status"),
        rs.getInt("retry_attempt"),
        rs.getString("locked_by"),
        Timestamps.read(rs, "locked_until"),
        Timestamps.read(rs, "created_at"),
        rs.getObject("created_by", Long.class),
        Timestamps.read(rs, "updated_at"),
        rs.getObject("updated_by", Long.class));
  }

  private static StoredRun run(ResultSet rs) throws SQLException {
    return new StoredRun(
        rs.getLong("id"),
        rs.getLong("schedule_id"),
        rs.getString("job_key"),
        rs.getObject("company_id", Long.class),
        TriggerType.valueOf(rs.getString("trigger_type")),
        rs.getObject("requested_by", Long.class),
        rs.getString("parameters_override"),
        RunStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt"),
        Timestamps.read(rs, "scheduled_for"),
        Timestamps.read(rs, "created_at"),
        Timestamps.read(rs, "started_at"),
        Timestamps.read(rs, "finished_at"),
        rs.getObject("duration_ms", Long.class),
        rs.getString("instance_id"),
        rs.getString("result_summary"),
        rs.getString("error_message"),
        rs.getString("error_detail"));
  }
}

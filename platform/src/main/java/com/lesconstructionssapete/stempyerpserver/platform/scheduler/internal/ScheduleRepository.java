package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

/**
 * scheduled_jobs, for the scheduler itself. Raw SQL, no rules.
 *
 * Time: every comparison uses an instant read from the database clock
 * ({@link #now}) and bound as a parameter, so several server instances agree
 * on "now" even when their own clocks drift. Soft-deleted rows are invisible.
 */
public class ScheduleRepository {

  /** Columns read by {@link #map}, prefixed with an alias so joins can use them. */
  static String columns(String alias) {
    String p = alias + ".";
    return String.join(", ", List.of("id", "company_id", "job_key", "name", "schedule_type", "cron_expression",
        "interval_value", "time_zone", "parameters", "is_enabled", "start_at", "end_at", "misfire_policy",
        "misfire_threshold_seconds", "timeout_seconds", "max_retries", "retry_backoff_seconds", "next_run_at",
        "schedule_fingerprint", "retry_attempt", "last_run_at", "last_status", "locked_by", "locked_until",
        "created_at").stream().map(c -> p + c).toList());
  }

  private static final String SELECT = "SELECT " + columns("s") + " FROM scheduled_jobs s WHERE s.deleted_at IS NULL";

  /** The database's current UTC time, whole seconds (what a DATETIME column stores). */
  public Instant now(UnitOfWork uow) {
    return Jdbc.queryFirst(uow, "SELECT UTC_TIMESTAMP() AS now",
        rs -> rs.getObject("now", LocalDateTime.class).toInstant(ZoneOffset.UTC)).orElseThrow();
  }

  /** Every row (the table is small): the scheduler checks each one's fingerprint on every poll. */
  public List<ScheduleRow> findAll(UnitOfWork uow) {
    return Jdbc.queryList(uow, SELECT + " ORDER BY s.id", ScheduleRepository::map);
  }

  public Optional<ScheduleRow> findById(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, SELECT + " AND s.id = ?", ScheduleRepository::map, id);
  }

  /**
   * Stores a recomputed next_run_at, only if the fingerprint is still the one
   * the caller saw: when two instances recompute the same row at once, the
   * first wins and the second changes nothing. A new fire time also resets the
   * retry count. Returns whether the row was updated.
   */
  public boolean updateNextRun(UnitOfWork uow, long id, String seenFingerprint, String newFingerprint,
      Instant nextRunAt) {
    return Jdbc.update(uow, """
        UPDATE scheduled_jobs SET next_run_at = ?, schedule_fingerprint = ?, retry_attempt = 0
        WHERE id = ? AND schedule_fingerprint <=> ?""",
        nextRunAt, newFingerprint, id, seenFingerprint) == 1;
  }

  /**
   * Locks up to {@code limit} enabled rows that are due and not leased, oldest
   * first. Rows another instance is claiming right now are skipped, not waited
   * for. Call in a short transaction that leases each row before committing.
   */
  public List<ScheduleRow> lockDue(UnitOfWork uow, Instant now, int limit, Collection<String> jobKeys) {
    String sql = SELECT
        + " AND s.is_enabled AND s.next_run_at <= ?"
        + " AND (s.locked_until IS NULL OR s.locked_until < ?)"
        + " AND s.job_key IN (" + placeholders(jobKeys) + ")"
        + " ORDER BY s.next_run_at, s.id LIMIT ? FOR UPDATE SKIP LOCKED";
    return Jdbc.queryList(uow, sql, ScheduleRepository::map, params(List.of(now, now), jobKeys, limit));
  }

  /** Moves next_run_at without leasing (a skipped misfire). */
  public void advance(UnitOfWork uow, long id, Instant nextRunAt) {
    Jdbc.update(uow, "UPDATE scheduled_jobs SET next_run_at = ? WHERE id = ?", nextRunAt, id);
  }

  /** Stops a row from firing until its next recompute (it became invalid after being claimed as due). */
  public void clearNextRun(UnitOfWork uow, long id) {
    Jdbc.update(uow, "UPDATE scheduled_jobs SET next_run_at = NULL WHERE id = ?", id);
  }

  /** Whether any row, deleted or not, uses this job key. */
  public boolean existsForJobKey(UnitOfWork uow, String jobKey) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM scheduled_jobs WHERE job_key = ? LIMIT 1", rs -> true, jobKey)
        .isPresent();
  }

  /** Inserts a system schedule; ignored if a concurrent instance inserted it (unique name). */
  public void insertDefault(UnitOfWork uow, String jobKey, String name, ScheduleType type, String expression,
      String timeZone, long createdBy) {
    Jdbc.update(uow, """
        INSERT IGNORE INTO scheduled_jobs (job_key, name, schedule_type, cron_expression, interval_value, time_zone,
          created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        jobKey, name, type, type == ScheduleType.CRON ? expression : null,
        type == ScheduleType.INTERVAL ? expression : null, timeZone, createdBy, createdBy);
  }

  /** "?, ?, ?" for a non-empty IN list. */
  static String placeholders(Collection<?> values) {
    if (values.isEmpty()) {
      throw new IllegalArgumentException("empty IN list");
    }
    return String.join(", ", Collections.nCopies(values.size(), "?"));
  }

  /** before..., values..., after... as one parameter array. */
  static Object[] params(List<?> before, Collection<?> values, Object... after) {
    List<Object> all = new ArrayList<>(before);
    all.addAll(values);
    all.addAll(Arrays.asList(after));
    return all.toArray();
  }

  /** Takes the lease and moves next_run_at on to the following fire time (NULL: none). */
  public void lease(UnitOfWork uow, long id, String instanceId, Instant lockedUntil, Instant nextRunAt) {
    Jdbc.update(uow, "UPDATE scheduled_jobs SET locked_by = ?, locked_until = ?, next_run_at = ? WHERE id = ?",
        instanceId, lockedUntil, nextRunAt, id);
  }

  /** Takes the lease without touching next_run_at (manual runs). */
  public void lease(UnitOfWork uow, long id, String instanceId, Instant lockedUntil) {
    Jdbc.update(uow, "UPDATE scheduled_jobs SET locked_by = ?, locked_until = ? WHERE id = ?",
        instanceId, lockedUntil, id);
  }

  /** Extends this instance's lease. False if the lease is no longer ours (expired and taken, or released). */
  public boolean renewLease(UnitOfWork uow, long id, String instanceId, Instant lockedUntil) {
    return Jdbc.update(uow, "UPDATE scheduled_jobs SET locked_until = ? WHERE id = ? AND locked_by = ?",
        lockedUntil, id, instanceId) == 1;
  }

  /**
   * Gives up this instance's lease and records the outcome. Returns false if
   * the lease was no longer ours (the outcome is then not recorded here).
   */
  public boolean release(UnitOfWork uow, long id, String instanceId, Instant lastRunAt, RunStatus lastStatus) {
    return Jdbc.update(uow, """
        UPDATE scheduled_jobs SET locked_by = NULL, locked_until = NULL, last_run_at = ?, last_status = ?
        WHERE id = ? AND locked_by = ?""",
        lastRunAt, lastStatus, id, instanceId) == 1;
  }

  /** Sets the retry count, and the next fire time when {@code nextRunAt} isn't null. */
  public void setRetry(UnitOfWork uow, long id, int retryAttempt, Instant nextRunAt) {
    if (nextRunAt == null) {
      Jdbc.update(uow, "UPDATE scheduled_jobs SET retry_attempt = ? WHERE id = ?", retryAttempt, id);
    } else {
      Jdbc.update(uow, "UPDATE scheduled_jobs SET retry_attempt = ?, next_run_at = ? WHERE id = ?",
          retryAttempt, nextRunAt, id);
    }
  }

  /** Locks rows whose lease ran out (its instance died or lost the database), skipping rows being handled. */
  public List<ScheduleRow> lockExpiredLeases(UnitOfWork uow, Instant now) {
    return Jdbc.queryList(uow, SELECT + " AND s.locked_until < ? ORDER BY s.id FOR UPDATE SKIP LOCKED",
        ScheduleRepository::map, now);
  }

  /** Clears a lease whoever holds it (after the reaper has dealt with its runs). */
  public void clearLease(UnitOfWork uow, long id, Instant lastRunAt, RunStatus lastStatus) {
    Jdbc.update(uow, """
        UPDATE scheduled_jobs SET locked_by = NULL, locked_until = NULL, last_run_at = ?, last_status = ?
        WHERE id = ?""",
        lastRunAt, lastStatus, id);
  }

  static ScheduleRow map(ResultSet rs) throws SQLException {
    return new ScheduleRow(
        rs.getLong("id"),
        rs.getObject("company_id", Long.class),
        rs.getString("job_key"),
        rs.getString("name"),
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
        rs.getInt("retry_backoff_seconds"),
        Timestamps.read(rs, "next_run_at"),
        rs.getString("schedule_fingerprint"),
        rs.getInt("retry_attempt"),
        Timestamps.read(rs, "last_run_at"),
        rs.getString("last_status"),
        rs.getString("locked_by"),
        Timestamps.read(rs, "locked_until"),
        Timestamps.read(rs, "created_at"));
  }
}

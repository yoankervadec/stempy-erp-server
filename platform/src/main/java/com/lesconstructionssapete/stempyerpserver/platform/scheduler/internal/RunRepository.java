package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;

/** scheduled_job_runs, the run history. Raw SQL, no rules. */
public class RunRepository {

  static final int MAX_MESSAGE = 1000;
  static final int MAX_DETAIL = 60_000; // TEXT holds 64 KB

  public record RunRow(
      long id,
      long scheduleId,
      String jobKey,
      Long companyId,
      TriggerType trigger,
      Long requestedBy,
      String parametersOverride,
      RunStatus status,
      int attempt,
      Instant scheduledFor,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Long durationMs,
      String instanceId,
      String resultSummary,
      String errorMessage,
      String errorDetail) {
  }

  /**
   * A run to record. {@code startedAt} and {@code instanceId} are null for a
   * QUEUED manual run; {@code requestedBy} and {@code parametersOverride} are
   * set only for MANUAL.
   */
  public record NewRun(long scheduleId, String jobKey, Long companyId, TriggerType trigger, Long requestedBy,
      String parametersOverride, RunStatus status, int attempt, Instant scheduledFor, Instant createdAt,
      Instant startedAt, String instanceId) {

    public NewRun {
      Objects.requireNonNull(trigger, "trigger");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(scheduledFor, "scheduledFor");
      Objects.requireNonNull(createdAt, "createdAt");
      if ((trigger == TriggerType.MANUAL) != (requestedBy != null)) {
        throw new IllegalArgumentException("requestedBy is required for MANUAL runs and only for them");
      }
    }

    /** One per fire and attempt, so the database refuses to run the same fire twice. */
    String fireKey() {
      return trigger == TriggerType.MANUAL ? null : scheduledFor.getEpochSecond() + ":" + attempt;
    }
  }

  /** A QUEUED manual run, locked together with its schedule, ready to start. */
  public record QueuedRun(long runId, Long requestedBy, String parametersOverride, Instant requestedAt,
      ScheduleRow schedule) {
  }

  private static final String COLUMNS = """
      id, schedule_id, job_key, company_id, trigger_type, requested_by, parameters_override, status, attempt,
      scheduled_for, created_at, started_at, finished_at, duration_ms, instance_id, result_summary,
      error_message, error_detail""";

  /**
   * Inserts a run and returns its id. A run inserted already finished (SKIPPED)
   * gets finished_at = createdAt.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException
   *         on a duplicate fire (same schedule, fire time and attempt)
   */
  public long insert(UnitOfWork uow, NewRun r) {
    return Jdbc.insert(uow, """
        INSERT INTO scheduled_job_runs (schedule_id, job_key, company_id, trigger_type, requested_by,
          parameters_override, status, attempt, scheduled_for, fire_key, created_at, started_at, finished_at,
          instance_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        r.scheduleId(), r.jobKey(), r.companyId(), r.trigger(), r.requestedBy(), r.parametersOverride(),
        r.status(), r.attempt(), r.scheduledFor(), r.fireKey(), r.createdAt(), r.startedAt(),
        r.status().isFinished() ? r.createdAt() : null, r.instanceId());
  }

  /**
   * Locks up to {@code limit} QUEUED manual runs whose schedule is not leased,
   * oldest first, with their schedule row. Several runs of one schedule can
   * come back: start only the first, the others stay queued.
   */
  public List<QueuedRun> lockQueued(UnitOfWork uow, Instant now, int limit, Collection<String> jobKeys) {
    String sql = "SELECT r.id AS run_id, r.requested_by, r.parameters_override, r.scheduled_for AS requested_at, "
        + ScheduleRepository.columns("s")
        + " FROM scheduled_job_runs r JOIN scheduled_jobs s ON s.id = r.schedule_id"
        + " WHERE r.status = 'QUEUED' AND s.deleted_at IS NULL"
        + " AND (s.locked_until IS NULL OR s.locked_until < ?)"
        + " AND s.job_key IN (" + ScheduleRepository.placeholders(jobKeys) + ")"
        + " ORDER BY r.id LIMIT ? FOR UPDATE SKIP LOCKED";
    return Jdbc.queryList(uow, sql,
        rs -> new QueuedRun(rs.getLong("run_id"), rs.getObject("requested_by", Long.class),
            rs.getString("parameters_override"), Timestamps.read(rs, "requested_at"), ScheduleRepository.map(rs)),
        ScheduleRepository.params(List.of(now), jobKeys, limit));
  }

  /** QUEUED -> RUNNING on this instance. */
  public void markStarted(UnitOfWork uow, long runId, String instanceId, Instant startedAt) {
    Jdbc.update(uow, """
        UPDATE scheduled_job_runs SET status = 'RUNNING', instance_id = ?, started_at = ?
        WHERE id = ? AND status = 'QUEUED'""",
        instanceId, startedAt, runId);
  }

  /**
   * Records the end of a RUNNING run. Texts are cut to fit their columns.
   * Returns false if the run was no longer RUNNING (e.g. already marked
   * ABANDONED by another instance): nothing is changed then.
   */
  public boolean finish(UnitOfWork uow, long runId, RunStatus status, Instant finishedAt, Long durationMs,
      String resultSummary, String errorMessage, String errorDetail) {
    if (!status.isFinished()) {
      throw new IllegalArgumentException("not a final status: " + status);
    }
    return Jdbc.update(uow, """
        UPDATE scheduled_job_runs
        SET status = ?, finished_at = ?, duration_ms = ?, result_summary = ?, error_message = ?, error_detail = ?
        WHERE id = ? AND status = 'RUNNING'""",
        status, finishedAt, durationMs, truncate(resultSummary, MAX_MESSAGE), truncate(errorMessage, MAX_MESSAGE),
        truncate(errorDetail, MAX_DETAIL), runId) == 1;
  }

  /**
   * Marks a schedule's RUNNING runs ABANDONED (their instance stopped renewing
   * the lease). Returns the runs changed, as they were before.
   */
  public List<RunRow> abandonRunning(UnitOfWork uow, long scheduleId, Instant now, String reason) {
    List<RunRow> running = Jdbc.queryList(uow,
        "SELECT " + COLUMNS + " FROM scheduled_job_runs WHERE schedule_id = ? AND status = 'RUNNING' FOR UPDATE",
        RunRepository::map, scheduleId);
    for (RunRow r : running) {
      finish(uow, r.id(), RunStatus.ABANDONED, now, null, null, reason, null);
    }
    return running;
  }

  public Optional<RunRow> findById(UnitOfWork uow, long runId) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + " FROM scheduled_job_runs WHERE id = ?",
        RunRepository::map, runId);
  }

  /** Newest first. */
  public List<RunRow> findBySchedule(UnitOfWork uow, long scheduleId, int limit) {
    return Jdbc.queryList(uow,
        "SELECT " + COLUMNS + " FROM scheduled_job_runs WHERE schedule_id = ? ORDER BY id DESC LIMIT ?",
        RunRepository::map, scheduleId, limit);
  }

  /** Deletes up to {@code limit} finished runs older than {@code cutoff}; call in a loop until it returns 0. */
  public int deleteFinishedBefore(UnitOfWork uow, Instant cutoff, int limit) {
    return Jdbc.update(uow, "DELETE FROM scheduled_job_runs WHERE finished_at < ? ORDER BY id LIMIT ?",
        cutoff, limit);
  }

  private static RunRow map(ResultSet rs) throws SQLException {
    return new RunRow(
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

  private static String truncate(String s, int max) {
    return (s == null || s.length() <= max) ? s : s.substring(0, max);
  }
}

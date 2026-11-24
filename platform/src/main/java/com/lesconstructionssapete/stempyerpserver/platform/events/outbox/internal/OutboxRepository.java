package com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxStatus;

/**
 * The SQL of the outbox table. "Now" is the database's clock
 * ({@link #now}), bound as a parameter, so instances with drifting clocks
 * agree on what is available and when a lease expires.
 *
 * Every update that ends an attempt is guarded by (locked_by, attempt): if the
 * lease expired and the row was taken over meanwhile, the late result is
 * dropped instead of overwriting the newer attempt.
 */
public final class OutboxRepository {

  static final int MAX_ERROR = 60_000; // TEXT holds 64 KB

  private static final String COLUMNS = """
      id, subscription_key, event_type, payload, company_id, status, attempt, available_at, locked_by,
      locked_until, last_error, created_at, processed_at""";

  /** The database's current UTC time, whole seconds (what a TIMESTAMP column stores). */
  public Instant now(UnitOfWork uow) {
    return Jdbc.queryFirst(uow, "SELECT UTC_TIMESTAMP() AS now",
        rs -> rs.getObject("now", LocalDateTime.class).toInstant(ZoneOffset.UTC)).orElseThrow();
  }

  /** A new PENDING row, available at once. */
  public long insert(UnitOfWork uow, String subscriptionKey, String eventType, String payload, long companyId) {
    return Jdbc.insert(uow, """
        INSERT INTO outbox (subscription_key, event_type, payload, company_id, status, attempt, available_at, created_at)
        VALUES (?, ?, ?, ?, 'PENDING', 0, UTC_TIMESTAMP(), UTC_TIMESTAMP())""",
        subscriptionKey, eventType, payload, companyId);
  }

  public Optional<OutboxRow> findById(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + " FROM outbox WHERE id = ?", OutboxRepository::map, id);
  }

  /**
   * Locks PENDING rows that are available, oldest first, for the given
   * subscription keys. Rows another instance is claiming right now are
   * skipped, not waited for.
   */
  public List<OutboxRow> lockAvailable(UnitOfWork uow, Instant now, int limit, Collection<String> keys) {
    if (keys.isEmpty() || limit <= 0) {
      return List.of();
    }
    String sql = "SELECT " + COLUMNS + " FROM outbox"
        + " WHERE status = 'PENDING' AND available_at <= ? AND subscription_key IN (" + placeholders(keys) + ")"
        + " ORDER BY available_at, id LIMIT ? FOR UPDATE SKIP LOCKED";
    return Jdbc.queryList(uow, sql, OutboxRepository::map, params(now, keys, limit));
  }

  /** Locks PROCESSING rows whose lease expired: their instance died or lost the database. */
  public List<OutboxRow> lockExpired(UnitOfWork uow, Instant now, int limit, Collection<String> keys) {
    if (keys.isEmpty()) {
      return List.of();
    }
    String sql = "SELECT " + COLUMNS + " FROM outbox"
        + " WHERE status = 'PROCESSING' AND locked_until < ? AND subscription_key IN (" + placeholders(keys) + ")"
        + " ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";
    return Jdbc.queryList(uow, sql, OutboxRepository::map, params(now, keys, limit));
  }

  /** Claims a locked row: PROCESSING, one more attempt, leased to {@code instance}. */
  public void markProcessing(UnitOfWork uow, long id, String instance, Instant lockedUntil) {
    Jdbc.update(uow, """
        UPDATE outbox SET status = 'PROCESSING', attempt = attempt + 1, locked_by = ?, locked_until = ?
        WHERE id = ?""", instance, lockedUntil, id);
  }

  public boolean markSucceeded(UnitOfWork uow, long id, String lockedBy, int attempt, Instant now) {
    return Jdbc.update(uow, """
        UPDATE outbox SET status = 'SUCCEEDED', processed_at = ?, locked_by = NULL, locked_until = NULL
        WHERE id = ? AND status = 'PROCESSING' AND locked_by = ? AND attempt = ?""",
        now, id, lockedBy, attempt) == 1;
  }

  /** Back to PENDING, available again at {@code availableAt}. */
  public boolean markRetry(UnitOfWork uow, long id, String lockedBy, int attempt, Instant availableAt, String error) {
    return Jdbc.update(uow, """
        UPDATE outbox SET status = 'PENDING', available_at = ?, last_error = ?, locked_by = NULL, locked_until = NULL
        WHERE id = ? AND status = 'PROCESSING' AND locked_by = ? AND attempt = ?""",
        availableAt, truncate(error), id, lockedBy, attempt) == 1;
  }

  public boolean markDead(UnitOfWork uow, long id, String lockedBy, int attempt, Instant now, String error) {
    return Jdbc.update(uow, """
        UPDATE outbox SET status = 'DEAD', processed_at = ?, last_error = ?, locked_by = NULL, locked_until = NULL
        WHERE id = ? AND status = 'PROCESSING' AND locked_by = ? AND attempt = ?""",
        now, truncate(error), id, lockedBy, attempt) == 1;
  }

  /**
   * Gives a claimed row back without counting the attempt (the instance is
   * shutting down): PENDING, available at once.
   */
  public boolean release(UnitOfWork uow, long id, String lockedBy, int attempt, Instant now) {
    return Jdbc.update(uow, """
        UPDATE outbox SET status = 'PENDING', attempt = attempt - 1, available_at = ?, locked_by = NULL,
          locked_until = NULL
        WHERE id = ? AND status = 'PROCESSING' AND locked_by = ? AND attempt = ?""",
        now, id, lockedBy, attempt) == 1;
  }

  /** Deletes up to {@code limit} rows in {@code status} processed before {@code cutoff}; returns how many. */
  public int deleteProcessedBefore(UnitOfWork uow, OutboxStatus status, Instant cutoff, int limit) {
    return Jdbc.update(uow, "DELETE FROM outbox WHERE status = ? AND processed_at < ? ORDER BY id LIMIT ?",
        status, cutoff, limit);
  }

  // ---- helpers ----

  static OutboxRow map(ResultSet rs) throws SQLException {
    return new OutboxRow(
        rs.getLong("id"),
        rs.getString("subscription_key"),
        rs.getString("event_type"),
        rs.getString("payload"),
        rs.getLong("company_id"),
        OutboxStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt"),
        Timestamps.read(rs, "available_at"),
        rs.getString("locked_by"),
        Timestamps.read(rs, "locked_until"),
        rs.getString("last_error"),
        Timestamps.read(rs, "created_at"),
        Timestamps.read(rs, "processed_at"));
  }

  private static String placeholders(Collection<?> values) {
    return String.join(", ", Collections.nCopies(values.size(), "?"));
  }

  private static Object[] params(Instant now, Collection<String> keys, int limit) {
    List<Object> params = new ArrayList<>();
    params.add(now);
    params.addAll(keys);
    params.add(limit);
    return params.toArray();
  }

  private static String truncate(String s) {
    return (s == null || s.length() <= MAX_ERROR) ? s : s.substring(0, MAX_ERROR);
  }
}

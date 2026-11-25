package com.lesconstructionssapete.stempyerpserver.platform.numbering.internal;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * SQL for number_series_counters (V9). Rows are created with INSERT IGNORE
 * and incremented with a plain UPDATE, never with INSERT ... ON DUPLICATE KEY
 * UPDATE: under REPEATABLE READ, concurrent first inserts of one key take
 * shared locks that then deadlock when they upgrade.
 */
public final class CounterRepository {

  /** A non-locking read: no gap lock is left behind when the counter doesn't exist. */
  public boolean exists(UnitOfWork uow, long seriesId, String scope) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM number_series_counters WHERE series_id = ? AND scope = ?",
        rs -> true, seriesId, scope).isPresent();
  }

  /** Creates the counter; ignored if it exists (a duplicate takes only a shared lock). */
  public void insertIgnoringDuplicate(UnitOfWork uow, long seriesId, String scope, long lastNumber) {
    Jdbc.update(uow, "INSERT IGNORE INTO number_series_counters (series_id, scope, last_number) VALUES (?, ?, ?)",
        seriesId, scope, lastNumber);
  }

  /**
   * Adds one and locks the row until the transaction ends. Returns the rows
   * changed: 0 when the counter doesn't exist (which, under REPEATABLE READ,
   * leaves a gap lock until the transaction ends).
   */
  public int increment(UnitOfWork uow, long seriesId, String scope) {
    return Jdbc.update(uow, """
        UPDATE number_series_counters SET last_number = last_number + 1
         WHERE series_id = ? AND scope = ?""", seriesId, scope);
  }

  /** The counter's value as this transaction sees it: after increment(), the number just taken. */
  public long lastNumber(UnitOfWork uow, long seriesId, String scope) {
    return Jdbc.queryFirst(uow, "SELECT last_number FROM number_series_counters WHERE series_id = ? AND scope = ?",
        rs -> rs.getLong(1), seriesId, scope)
        .orElseThrow(() -> new IllegalStateException("counter " + seriesId + "/" + scope + " vanished"));
  }
}

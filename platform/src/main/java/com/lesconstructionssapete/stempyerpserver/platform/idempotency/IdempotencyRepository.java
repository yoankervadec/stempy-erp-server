package com.lesconstructionssapete.stempyerpserver.platform.idempotency;

import java.time.Instant;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** SQL for idempotency_keys (V12). */
final class IdempotencyRepository {

  record Stored(String requestHash, String result) {
  }

  /**
   * Inserts the key; false if it exists. INSERT IGNORE: a duplicate waits for
   * the row's transaction and then takes only a shared lock.
   */
  boolean insertIfAbsent(UnitOfWork uow, IdempotencyKey k, String requestHash, Instant now) {
    return Jdbc.update(uow, """
        INSERT IGNORE INTO idempotency_keys (company_id, user_id, scope, idem_key, request_hash, created_at)
        VALUES (?, ?, ?, ?, ?, ?)""", k.companyId(), k.userId(), k.scope(), k.key(), requestHash, now) == 1;
  }

  /** A current read (FOR SHARE): sees the row a concurrent transaction just committed. */
  Optional<Stored> find(UnitOfWork uow, IdempotencyKey k) {
    return Jdbc.queryFirst(uow, """
        SELECT request_hash, result FROM idempotency_keys
         WHERE company_id = ? AND user_id = ? AND scope = ? AND idem_key = ? FOR SHARE""",
        rs -> new Stored(rs.getString(1), rs.getString(2)), k.companyId(), k.userId(), k.scope(), k.key());
  }

  void setResult(UnitOfWork uow, IdempotencyKey k, String result) {
    Jdbc.update(uow, """
        UPDATE idempotency_keys SET result = ?
         WHERE company_id = ? AND user_id = ? AND scope = ? AND idem_key = ?""",
        result, k.companyId(), k.userId(), k.scope(), k.key());
  }

  int deleteBefore(UnitOfWork uow, Instant cutoff, int limit) {
    return Jdbc.update(uow, "DELETE FROM idempotency_keys WHERE created_at < ? ORDER BY created_at LIMIT ?",
        cutoff, limit);
  }
}

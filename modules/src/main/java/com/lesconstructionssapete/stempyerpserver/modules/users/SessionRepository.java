package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Instant;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * user_sessions. Every time comparison uses an instant bound from Java (never
 * NOW() in SQL), so the rules don't depend on the MySQL session time zone.
 */
public class SessionRepository {

  public record NewSession(long companyId, long userId, byte[] tokenHash, Instant createdAt, Instant expiresAt,
      String clientIp, String userAgent) {
  }

  public record ActiveSession(long id, long userId, long companyId, Instant lastSeenAt) {
  }

  public void insert(UnitOfWork uow, NewSession s) {
    Jdbc.update(uow, """
        INSERT INTO user_sessions (company_id, user_id, token_hash, created_at, last_seen_at, expires_at,
                                   client_ip, user_agent)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        s.companyId(), s.userId(), s.tokenHash(), s.createdAt(), s.createdAt(), s.expiresAt(),
        truncate(s.clientIp(), 45), truncate(s.userAgent(), 255));
  }

  /**
   * The session for this token if it is live: not revoked, not past its
   * lifetime, used since {@code idleCutoff}, and its user and company still
   * enabled.
   */
  public Optional<ActiveSession> findActive(UnitOfWork uow, byte[] tokenHash, Instant now, Instant idleCutoff) {
    return Jdbc.queryFirst(uow, """
        SELECT s.id, s.user_id, s.company_id, s.last_seen_at
        FROM user_sessions s
        JOIN users u ON u.id = s.user_id AND u.company_id = s.company_id
        JOIN companies c ON c.id = s.company_id
        WHERE s.token_hash = ?
          AND s.revoked_at IS NULL
          AND s.expires_at > ?
          AND s.last_seen_at > ?
          AND u.is_enabled = TRUE AND u.deleted_at IS NULL
          AND c.is_enabled = TRUE""",
        rs -> new ActiveSession(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("company_id"),
            Timestamps.read(rs, "last_seen_at")),
        tokenHash, now, idleCutoff);
  }

  public void touch(UnitOfWork uow, long sessionId, Instant now) {
    Jdbc.update(uow, "UPDATE user_sessions SET last_seen_at = ? WHERE id = ?", now, sessionId);
  }

  public int revoke(UnitOfWork uow, byte[] tokenHash, Instant now) {
    return Jdbc.update(uow,
        "UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", now, tokenHash);
  }

  public int revokeAllForUser(UnitOfWork uow, long userId, Instant now) {
    return Jdbc.update(uow,
        "UPDATE user_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", now, userId);
  }

  /** Housekeeping at login: drops this user's sessions that can never be used again. */
  public int deleteDeadForUser(UnitOfWork uow, long userId, Instant now, Instant idleCutoff) {
    return Jdbc.update(uow, "DELETE FROM user_sessions WHERE user_id = ?"
        + " AND (revoked_at IS NOT NULL OR expires_at <= ? OR last_seen_at <= ?)", userId, now, idleCutoff);
  }

  private static String truncate(String s, int max) {
    return (s == null || s.length() <= max) ? s : s.substring(0, max);
  }
}

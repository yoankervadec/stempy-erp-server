package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
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
    String sql = """
        INSERT INTO user_sessions (company_id, user_id, token_hash, created_at, last_seen_at, expires_at,
                                   client_ip, user_agent)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      ps.setLong(1, s.companyId());
      ps.setLong(2, s.userId());
      ps.setBytes(3, s.tokenHash());
      Timestamps.set(ps, 4, s.createdAt());
      Timestamps.set(ps, 5, s.createdAt());
      Timestamps.set(ps, 6, s.expiresAt());
      ps.setString(7, truncate(s.clientIp(), 45));
      ps.setString(8, truncate(s.userAgent(), 255));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /**
   * The session for this token if it is live: not revoked, not past its
   * lifetime, used since {@code idleCutoff}, and its user and company still
   * enabled.
   */
  public Optional<ActiveSession> findActive(UnitOfWork uow, byte[] tokenHash, Instant now, Instant idleCutoff) {
    String sql = """
        SELECT s.id, s.user_id, s.company_id, s.last_seen_at
        FROM user_sessions s
        JOIN users u ON u.id = s.user_id AND u.company_id = s.company_id
        JOIN companies c ON c.id = s.company_id
        WHERE s.token_hash = ?
          AND s.revoked_at IS NULL
          AND s.expires_at > ?
          AND s.last_seen_at > ?
          AND u.is_enabled = TRUE AND u.deleted_at IS NULL
          AND c.is_enabled = TRUE""";
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      ps.setBytes(1, tokenHash);
      Timestamps.set(ps, 2, now);
      Timestamps.set(ps, 3, idleCutoff);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(new ActiveSession(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("company_id"),
            Timestamps.get(rs, "last_seen_at")));
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  public void touch(UnitOfWork uow, long sessionId, Instant now) {
    try (PreparedStatement ps = uow.connection().prepareStatement(
        "UPDATE user_sessions SET last_seen_at = ? WHERE id = ?")) {
      Timestamps.set(ps, 1, now);
      ps.setLong(2, sessionId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  public int revoke(UnitOfWork uow, byte[] tokenHash, Instant now) {
    try (PreparedStatement ps = uow.connection().prepareStatement(
        "UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")) {
      Timestamps.set(ps, 1, now);
      ps.setBytes(2, tokenHash);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  public int revokeAllForUser(UnitOfWork uow, long userId, Instant now) {
    try (PreparedStatement ps = uow.connection().prepareStatement(
        "UPDATE user_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")) {
      Timestamps.set(ps, 1, now);
      ps.setLong(2, userId);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /** Housekeeping at login: drops this user's sessions that can never be used again. */
  public int deleteDeadForUser(UnitOfWork uow, long userId, Instant now, Instant idleCutoff) {
    try (PreparedStatement ps = uow.connection().prepareStatement(
        "DELETE FROM user_sessions WHERE user_id = ?"
            + " AND (revoked_at IS NOT NULL OR expires_at <= ? OR last_seen_at <= ?)")) {
      ps.setLong(1, userId);
      Timestamps.set(ps, 2, now);
      Timestamps.set(ps, 3, idleCutoff);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  private static String truncate(String s, int max) {
    return (s == null || s.length() <= max) ? s : s.substring(0, max);
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory.UserSummary;

/** users + user_credentials, for authentication. Raw JDBC, no rules. */
public class UserAccountRepository {

  /**
   * What login needs to decide. {@code active} folds every "may log in" flag:
   * user enabled and not deleted, credential enabled, and at least one company
   * the user may work in (enabled membership in an enabled company).
   */
  public record LoginCandidate(UserProfile user, String passwordHash, boolean active) {
  }

  public record UserRef(long id) {
  }

  /** The user's own language and the company's default, as stored (codes or null). */
  public record Languages(String user, String company) {
  }

  /** Latest non-deleted credential of the user with this user number. */
  public Optional<LoginCandidate> findLoginCandidate(UnitOfWork uow, String userNo) {
    return Jdbc.queryFirst(uow, """
        SELECT u.id, u.user_no, u.name, u.language, cr.password_hash,
               (u.is_enabled AND u.deleted_at IS NULL AND cr.is_enabled AND EXISTS (
                  SELECT 1 FROM user_companies uc JOIN companies c ON c.id = uc.company_id
                  WHERE uc.user_id = u.id AND uc.is_enabled AND c.is_enabled)) AS active
        FROM users u
        JOIN user_credentials cr ON cr.user_id = u.id AND cr.deleted_at IS NULL
        WHERE u.user_no = ?
        ORDER BY cr.id DESC
        LIMIT 1""",
        rs -> new LoginCandidate(profile(rs), rs.getString("password_hash"), rs.getBoolean("active")),
        userNo);
  }

  public Optional<UserProfile> findProfile(UnitOfWork uow, long userId) {
    return Jdbc.queryFirst(uow, "SELECT id, user_no, name, language FROM users WHERE id = ? AND deleted_at IS NULL",
        UserAccountRepository::profile, userId);
  }

  public Optional<UserRef> findByUserNo(UnitOfWork uow, String userNo) {
    return Jdbc.queryFirst(uow, "SELECT id FROM users WHERE user_no = ? AND deleted_at IS NULL",
        rs -> new UserRef(rs.getLong("id")), userNo);
  }

  /** Id, user number and name of these users, deleted ones included; at most 1000 ids at a time. */
  public List<UserSummary> findSummaries(UnitOfWork uow, Collection<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return Jdbc.queryList(uow, "SELECT id, user_no, name FROM users WHERE id IN ("
        + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")",
        rs -> new UserSummary(rs.getLong("id"), rs.getString("user_no"), rs.getString("name")), ids.toArray());
  }

  /** The user with this user number, deleted or not (user numbers are unique across both). */
  public Optional<UserRef> findAnyByUserNo(UnitOfWork uow, String userNo) {
    return Jdbc.queryFirst(uow, "SELECT id FROM users WHERE user_no = ?", rs -> new UserRef(rs.getLong("id")), userNo);
  }

  /**
   * Inserts a disabled user that is its own creator (created_by / updated_by
   * point at itself, like the first user of an install), with the next free
   * id. Does nothing if the user number, or that id, was taken meanwhile by a
   * concurrent insert: read it back with findAnyByUserNo.
   */
  public void insertSelfCreatedDisabledUser(UnitOfWork uow, String userNo, String name) {
    Jdbc.update(uow, """
        INSERT IGNORE INTO users (id, user_no, name, is_enabled, created_by, updated_by)
        SELECT n.id, ?, ?, FALSE, n.id, n.id
        FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM users) n""",
        userNo, name);
  }

  /**
   * A live user's own language and, when {@code companyId} isn't null, that
   * company's default. Empty if the user (or the company) doesn't exist.
   */
  public Optional<Languages> findLanguages(UnitOfWork uow, long userId, Long companyId) {
    if (companyId == null) {
      return Jdbc.queryFirst(uow, "SELECT language FROM users WHERE id = ? AND deleted_at IS NULL",
          rs -> new Languages(rs.getString("language"), null), userId);
    }
    return Jdbc.queryFirst(uow, """
        SELECT u.language, c.default_language
        FROM users u JOIN companies c ON c.id = ?
        WHERE u.id = ? AND u.deleted_at IS NULL""",
        rs -> new Languages(rs.getString("language"), rs.getString("default_language")), companyId, userId);
  }

  /**
   * A live user's own language, locking the row: empty if there is no such
   * user, an empty inner Optional if they chose none.
   */
  public Optional<Optional<String>> findLanguageForUpdate(UnitOfWork uow, long userId) {
    return Jdbc.queryFirst(uow, "SELECT language FROM users WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
        rs -> Optional.ofNullable(rs.getString("language")), userId);
  }

  /** The user is their own updater: only they change their language. */
  public void setLanguage(UnitOfWork uow, long userId, String language, Instant now) {
    Jdbc.update(uow, "UPDATE users SET language = ?, updated_at = ?, updated_by = id WHERE id = ?",
        language, now, userId);
  }

  /** Soft-deletes the current credential(s) and inserts the new one (keeps history). */
  public void replacePassword(UnitOfWork uow, UserRef user, String passwordHash, Instant now) {
    Jdbc.update(uow, "UPDATE user_credentials SET deleted_at = ? WHERE user_id = ? AND deleted_at IS NULL",
        now, user.id());
    Jdbc.update(uow, "INSERT INTO user_credentials (user_id, password_hash, created_at) VALUES (?, ?, ?)",
        user.id(), passwordHash, now);
  }

  private static UserProfile profile(ResultSet rs) throws SQLException {
    return new UserProfile(rs.getLong("id"), rs.getString("user_no"), rs.getString("name"),
        rs.getString("language"));
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** users + user_credentials, for authentication. Raw JDBC, no rules. */
public class UserAccountRepository {

  /**
   * What login needs to decide. {@code active} folds every "may log in" flag:
   * user enabled and not deleted, credential enabled, company enabled.
   */
  public record LoginCandidate(UserProfile user, String passwordHash, boolean active) {
  }

  public record UserRef(long id, long companyId) {
  }

  /** Latest non-deleted credential of the user with this user number. */
  public Optional<LoginCandidate> findLoginCandidate(UnitOfWork uow, String userNo) {
    String sql = """
        SELECT u.id, u.company_id, u.user_no, u.name, cr.password_hash,
               (u.is_enabled AND u.deleted_at IS NULL AND cr.is_enabled AND co.is_enabled) AS active
        FROM users u
        JOIN user_credentials cr ON cr.user_id = u.id AND cr.company_id = u.company_id AND cr.deleted_at IS NULL
        JOIN companies co ON co.id = u.company_id
        WHERE u.user_no = ?
        ORDER BY cr.id DESC
        LIMIT 1""";
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      ps.setString(1, userNo);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(new LoginCandidate(profile(rs), rs.getString("password_hash"), rs.getBoolean("active")));
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  public Optional<UserProfile> findProfile(UnitOfWork uow, long userId, long companyId) {
    String sql = "SELECT id, company_id, user_no, name FROM users"
        + " WHERE id = ? AND company_id = ? AND deleted_at IS NULL";
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      ps.setLong(1, userId);
      ps.setLong(2, companyId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(profile(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  public Optional<UserRef> findByUserNo(UnitOfWork uow, String userNo) {
    String sql = "SELECT id, company_id FROM users WHERE user_no = ? AND deleted_at IS NULL";
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      ps.setString(1, userNo);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(new UserRef(rs.getLong("id"), rs.getLong("company_id"))) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /** Soft-deletes the current credential(s) and inserts the new one (keeps history). */
  public void replacePassword(UnitOfWork uow, UserRef user, String passwordHash, Instant now) {
    try (PreparedStatement retire = uow.connection().prepareStatement(
        "UPDATE user_credentials SET deleted_at = ? WHERE user_id = ? AND deleted_at IS NULL");
        PreparedStatement insert = uow.connection().prepareStatement(
            "INSERT INTO user_credentials (company_id, user_id, password_hash, created_at) VALUES (?, ?, ?, ?)")) {
      Timestamps.set(retire, 1, now);
      retire.setLong(2, user.id());
      retire.executeUpdate();

      insert.setLong(1, user.companyId());
      insert.setLong(2, user.id());
      insert.setString(3, passwordHash);
      Timestamps.set(insert, 4, now);
      insert.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  private static UserProfile profile(ResultSet rs) throws SQLException {
    return new UserProfile(rs.getLong("id"), rs.getLong("company_id"), rs.getString("user_no"), rs.getString("name"));
  }
}

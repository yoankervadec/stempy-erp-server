package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain-SQL helpers for repositories: prepare, bind, run, map, and turn
 * SQLException into DataAccessException. Hand-written SQL stays in the
 * repository; only the ceremony lives here.
 *
 * Parameters are bound in order by their Java type: an Instant goes through
 * {@link Timestamps} (UTC), an Enum is stored by name, and anything else
 * (Long, String, Boolean, BigDecimal, LocalDate, byte[], null) is passed to
 * setObject.
 *
 * <pre>
 * Jdbc.update(uow, "UPDATE user_sessions SET last_seen_at = ? WHERE id = ?", now, sessionId);
 * Optional&lt;UserRef&gt; ref = Jdbc.queryFirst(uow, "SELECT id, company_id FROM users WHERE user_no = ?",
 *     rs -&gt; new UserRef(rs.getLong("id"), rs.getLong("company_id")), userNo);
 * </pre>
 */
public final class Jdbc {

  @FunctionalInterface
  public interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  private Jdbc() {
  }

  /** INSERT / UPDATE / DELETE. Returns the number of rows affected. */
  public static int update(UnitOfWork uow, String sql, Object... params) {
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      bind(ps, params);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /** A single-row INSERT into a table with an AUTO_INCREMENT key. Returns the new key. */
  public static long insert(UnitOfWork uow, String sql, Object... params) {
    try (PreparedStatement ps = uow.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      bind(ps, params);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new DataAccessException("INSERT returned no generated key", new SQLException(sql));
        }
        return keys.getLong(1);
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /** The first row, if any. Use ORDER BY / LIMIT 1 when several rows can match. */
  public static <T> Optional<T> queryFirst(UnitOfWork uow, String sql, RowMapper<T> mapper, Object... params) {
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  public static <T> List<T> queryList(UnitOfWork uow, String sql, RowMapper<T> mapper, Object... params) {
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(mapper.map(rs));
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  private static void bind(PreparedStatement ps, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      Object p = params[i];
      switch (p) {
        case Instant instant -> Timestamps.bind(ps, i + 1, instant);
        case Enum<?> e -> ps.setString(i + 1, e.name());
        case null, default -> ps.setObject(i + 1, p);
      }
    }
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Instant;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** user_preferences: one JSON text per user and key. Raw JDBC, no rules. */
public class PreferenceRepository {

  /** A stored row; {@code valueJson} is the JSON text as written. */
  public record StoredPreference(String key, String valueJson, Instant updatedAt) {
  }

  /** The user's preferences, by key. */
  public List<StoredPreference> findAll(UnitOfWork uow, long userId) {
    return Jdbc.queryList(uow,
        "SELECT pref_key, value_json, updated_at FROM user_preferences WHERE user_id = ? ORDER BY pref_key",
        rs -> new StoredPreference(rs.getString("pref_key"), rs.getString("value_json"),
            Timestamps.read(rs, "updated_at")),
        userId);
  }

  /**
   * Locks the live user's row, so that writes of one user's preferences run one
   * at a time (the key count stays within its limit). False if there is no
   * such user.
   */
  public boolean lockUser(UnitOfWork uow, long userId) {
    return Jdbc.queryFirst(uow, "SELECT id FROM users WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
        rs -> rs.getLong("id"), userId).isPresent();
  }

  public boolean exists(UnitOfWork uow, long userId, String key) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM user_preferences WHERE user_id = ? AND pref_key = ?",
        rs -> true, userId, key).orElse(false);
  }

  public int count(UnitOfWork uow, long userId) {
    return Jdbc.queryFirst(uow, "SELECT COUNT(*) AS n FROM user_preferences WHERE user_id = ?",
        rs -> rs.getInt("n"), userId).orElse(0);
  }

  /** Creates or replaces the preference. */
  public void upsert(UnitOfWork uow, long userId, String key, String valueJson, Instant now) {
    Jdbc.update(uow, """
        INSERT INTO user_preferences (user_id, pref_key, value_json, updated_at) VALUES (?, ?, ?, ?) AS n
        ON DUPLICATE KEY UPDATE value_json = n.value_json, updated_at = n.updated_at""",
        userId, key, valueJson, now);
  }

  /** Removes the preference; nothing happens if there is none. */
  public void delete(UnitOfWork uow, long userId, String key) {
    Jdbc.update(uow, "DELETE FROM user_preferences WHERE user_id = ? AND pref_key = ?", userId, key);
  }

}

package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;

/**
 * SQL for the numbering admin API, on the V9 tables (number_series,
 * number_series_counters). The numbering engine (platform.numbering.internal)
 * has its own; this one never takes numbers.
 */
public class NumberSeriesAdminRepository {

  private static final String COLUMNS = """
      s.id, s.version, s.company_id, s.document_type, s.code, s.description, s.pattern, s.mode, s.start_value,
      s.is_default, s.is_enabled, s.created_at, s.created_by, s.updated_at, s.updated_by,
      EXISTS (SELECT 1 FROM number_series_counters c WHERE c.series_id = s.id) AS used""";

  private static StoredSeries map(ResultSet rs) throws SQLException {
    var fields = new SeriesFields(rs.getString("code"), rs.getString("description"), rs.getString("pattern"),
        Mode.valueOf(rs.getString("mode")), rs.getLong("start_value"), rs.getBoolean("is_default"),
        rs.getBoolean("is_enabled"));
    return new StoredSeries(rs.getLong("id"), rs.getInt("version"), rs.getLong("company_id"),
        rs.getString("document_type"), fields, rs.getBoolean("used"), Timestamps.read(rs, "created_at"),
        rs.getObject("created_by", Long.class), Timestamps.read(rs, "updated_at"),
        rs.getObject("updated_by", Long.class));
  }

  // ---- series ----

  /** The company's series, by document type then code. */
  public List<StoredSeries> list(UnitOfWork uow, long companyId, String documentType, int limit, int offset) {
    List<Object> params = new ArrayList<>(List.of(companyId));
    String filter = "";
    if (documentType != null) {
      filter = " AND s.document_type = ?";
      params.add(documentType);
    }
    params.add(limit);
    params.add(offset);
    return Jdbc.queryList(uow, "SELECT " + COLUMNS + " FROM number_series s WHERE s.company_id = ?" + filter
        + " ORDER BY s.document_type, s.code LIMIT ? OFFSET ?", NumberSeriesAdminRepository::map, params.toArray());
  }

  public Optional<StoredSeries> find(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + " FROM number_series s WHERE s.id = ?",
        NumberSeriesAdminRepository::map, id);
  }

  /**
   * Locks the series row until the transaction ends. A counter's first insert
   * reads this row (foreign key check), so while it is held no series can
   * become "used" behind an update's back.
   */
  public Optional<StoredSeries> findForUpdate(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + " FROM number_series s WHERE s.id = ? FOR UPDATE",
        NumberSeriesAdminRepository::map, id);
  }

  public boolean codeTaken(UnitOfWork uow, long companyId, String documentType, String code) {
    return Jdbc.queryFirst(uow,
        "SELECT 1 FROM number_series WHERE company_id = ? AND document_type = ? AND code = ?",
        rs -> true, companyId, documentType, code).isPresent();
  }

  /** The default series (enabled or not), locked until the transaction ends. */
  public Optional<StoredSeries> findDefaultForUpdate(UnitOfWork uow, long companyId, String documentType) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + """
         FROM number_series s
        WHERE s.company_id = ? AND s.document_type = ? AND s.is_default
        FOR UPDATE""", NumberSeriesAdminRepository::map, companyId, documentType);
  }

  public long insert(UnitOfWork uow, long companyId, String documentType, SeriesFields f, long userId,
      Instant now) {
    return Jdbc.insert(uow, """
        INSERT INTO number_series (company_id, document_type, code, description, pattern, mode, start_value,
          is_default, is_enabled, created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        companyId, documentType, f.code(), f.description(), f.pattern(), f.mode(), f.startValue(), f.isDefault(),
        f.isEnabled(), now, userId, now, userId);
  }

  /** Replaces the editable columns if the row is still at {@code version}. Returns the rows changed. */
  public int update(UnitOfWork uow, long id, int version, SeriesFields f, long userId, Instant now) {
    return Jdbc.update(uow, """
        UPDATE number_series
           SET description = ?, pattern = ?, mode = ?, start_value = ?, is_default = ?, is_enabled = ?,
               version = version + 1, updated_at = ?, updated_by = ?
         WHERE id = ? AND version = ?""",
        f.description(), f.pattern(), f.mode(), f.startValue(), f.isDefault(), f.isEnabled(), now, userId, id,
        version);
  }

  /** Takes the default flag off a series (another one becomes the default). */
  public void clearDefault(UnitOfWork uow, long id, long userId, Instant now) {
    Jdbc.update(uow, """
        UPDATE number_series SET is_default = FALSE, version = version + 1, updated_at = ?, updated_by = ?
         WHERE id = ?""", now, userId, id);
  }

  // ---- counters ----

  public List<StoredCounter> counters(UnitOfWork uow, long seriesId) {
    return Jdbc.queryList(uow, """
        SELECT scope, last_number, updated_at FROM number_series_counters WHERE series_id = ? ORDER BY scope""",
        rs -> new StoredCounter(rs.getString("scope"), rs.getLong("last_number"), Timestamps.read(rs, "updated_at")),
        seriesId);
  }

  /** Creates a counter at {@code lastNumber}; false if it already exists (it is left unchanged). */
  public boolean insertCounterIfAbsent(UnitOfWork uow, long seriesId, String scope, long lastNumber) {
    return Jdbc.update(uow, """
        INSERT IGNORE INTO number_series_counters (series_id, scope, last_number) VALUES (?, ?, ?)""",
        seriesId, scope, lastNumber) == 1;
  }

  /**
   * The counter's last number, locked until the transaction ends: an open
   * GAPLESS transaction holding it makes this wait.
   */
  public long lastNumberForUpdate(UnitOfWork uow, long seriesId, String scope) {
    return Jdbc.queryFirst(uow, """
        SELECT last_number FROM number_series_counters WHERE series_id = ? AND scope = ? FOR UPDATE""",
        rs -> rs.getLong(1), seriesId, scope)
        .orElseThrow(() -> new IllegalStateException("counter " + seriesId + "/" + scope + " vanished"));
  }

  public void setLastNumber(UnitOfWork uow, long seriesId, String scope, long lastNumber) {
    Jdbc.update(uow, "UPDATE number_series_counters SET last_number = ? WHERE series_id = ? AND scope = ?",
        lastNumber, seriesId, scope);
  }
}

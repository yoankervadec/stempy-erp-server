package com.lesconstructionssapete.stempyerpserver.platform.numbering.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;

/** SQL for number_series (V9). No rules: JdbcDocumentNumbers decides. */
public final class SeriesRepository {

  /** One number_series row, as numbering needs it. */
  public record SeriesRow(long id, long companyId, String documentType, String code, String pattern, Mode mode,
      long startValue, boolean isDefault, boolean isEnabled) {
  }

  private static final String COLUMNS = """
      id, company_id, document_type, code, pattern, mode, start_value, is_default, is_enabled""";

  private static SeriesRow map(ResultSet rs) throws SQLException {
    return new SeriesRow(rs.getLong("id"), rs.getLong("company_id"), rs.getString("document_type"),
        rs.getString("code"), rs.getString("pattern"), Mode.valueOf(rs.getString("mode")),
        rs.getLong("start_value"), rs.getBoolean("is_default"), rs.getBoolean("is_enabled"));
  }

  /** The enabled series with this code. */
  public Optional<SeriesRow> findEnabled(UnitOfWork uow, long companyId, String documentType, String code) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + """
         FROM number_series
        WHERE company_id = ? AND document_type = ? AND code = ? AND is_enabled""",
        SeriesRepository::map, companyId, documentType, code);
  }

  /**
   * The enabled default series. {@code locking}: a current read (FOR SHARE),
   * which sees rows committed after this transaction's snapshot.
   */
  public Optional<SeriesRow> findDefault(UnitOfWork uow, long companyId, String documentType, boolean locking) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + """
         FROM number_series
        WHERE company_id = ? AND document_type = ? AND is_default AND is_enabled""" + (locking ? " FOR SHARE" : ""),
        SeriesRepository::map, companyId, documentType);
  }

  /** Whether the company has a default series for the type, enabled or not. */
  public boolean existsDefault(UnitOfWork uow, long companyId, String documentType) {
    return Jdbc.queryFirst(uow, """
        SELECT 1 FROM number_series WHERE company_id = ? AND document_type = ? AND is_default LIMIT 1""",
        rs -> true, companyId, documentType).isPresent();
  }

  /**
   * Inserts the type's default series as the company's default. Ignored when
   * its code is taken, or a concurrent transaction inserted a default first.
   */
  public void insertDefaultIgnoringDuplicates(UnitOfWork uow, long companyId, DocumentType type) {
    DocumentType.DefaultSeries d = type.defaultSeries();
    Jdbc.update(uow, """
        INSERT IGNORE INTO number_series (company_id, document_type, code, description, pattern, mode, is_default)
        VALUES (?, ?, ?, ?, ?, ?, TRUE)""",
        companyId, type.key(), d.code(), type.description(), d.pattern(), d.mode());
  }
}

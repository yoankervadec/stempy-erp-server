package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** SQL for gl_journal_entries and gl_journal_lines (V13). */
public class JournalRepository {

  private static final String ENTRY_COLUMNS = """
      id, company_id, entry_no, posting_date, period_id, description, source_type, source_id, source_no,
      reversal_of_id, reversed_by_id, currency_code, total, posted_at, posted_by""";

  private static StoredEntry entry(ResultSet rs) throws SQLException {
    return new StoredEntry(rs.getLong("id"), rs.getLong("company_id"), rs.getString("entry_no"),
        rs.getObject("posting_date", LocalDate.class), rs.getLong("period_id"), rs.getString("description"),
        rs.getString("source_type"), rs.getObject("source_id", Long.class), rs.getString("source_no"),
        rs.getObject("reversal_of_id", Long.class), rs.getObject("reversed_by_id", Long.class),
        rs.getString("currency_code"), rs.getBigDecimal("total"), Timestamps.read(rs, "posted_at"),
        rs.getLong("posted_by"));
  }

  /** The company's base currency (companies.base_currency_code). */
  public Optional<String> companyCurrency(UnitOfWork uow, long companyId) {
    return Jdbc.queryFirst(uow, "SELECT base_currency_code FROM companies WHERE id = ?", rs -> rs.getString(1),
        companyId);
  }

  public long insertEntry(UnitOfWork uow, long companyId, String entryNo, LocalDate postingDate, long periodId,
      String description, String sourceType, Long sourceId, String sourceNo, Long reversalOfId, String currency,
      BigDecimal total, Instant postedAt, long postedBy) {
    return Jdbc.insert(uow, """
        INSERT INTO gl_journal_entries (company_id, entry_no, posting_date, period_id, description, source_type,
          source_id, source_no, reversal_of_id, currency_code, total, posted_at, posted_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        companyId, entryNo, postingDate, periodId, description, sourceType, sourceId, sourceNo, reversalOfId,
        currency, total, postedAt, postedBy);
  }

  public void insertLine(UnitOfWork uow, long entryId, int lineNo, long companyId, LocalDate postingDate,
      PostingLine line) {
    Jdbc.update(uow, """
        INSERT INTO gl_journal_lines (entry_id, line_no, company_id, posting_date, account_id, debit, credit,
          description, dimension_set_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        entryId, lineNo, companyId, postingDate, line.accountId(), line.debit(), line.credit(), line.description(),
        line.dimensionSetId());
  }

  public Optional<StoredEntry> find(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + ENTRY_COLUMNS + " FROM gl_journal_entries WHERE id = ?",
        JournalRepository::entry, id);
  }

  /** Locks the entry until the transaction ends: two reversals of it can't race. */
  public Optional<StoredEntry> findForUpdate(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + ENTRY_COLUMNS + " FROM gl_journal_entries WHERE id = ? FOR UPDATE",
        JournalRepository::entry, id);
  }

  public List<StoredLine> lines(UnitOfWork uow, long entryId) {
    return Jdbc.queryList(uow, """
        SELECT l.line_no, l.account_id, a.account_no, a.name, l.debit, l.credit, l.description, l.dimension_set_id
          FROM gl_journal_lines l JOIN gl_accounts a ON a.id = l.account_id
         WHERE l.entry_id = ? ORDER BY l.line_no""",
        rs -> new StoredLine(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
            rs.getBigDecimal(6), rs.getString(7), rs.getObject(8, Long.class)),
        entryId);
  }

  /**
   * The company's entries, newest posting date first (then newest number).
   *
   * @param from       null: no lower bound
   * @param to         null: no upper bound
   * @param sourceType null: any
   */
  public List<StoredEntry> list(UnitOfWork uow, long companyId, LocalDate from, LocalDate to, String sourceType,
      int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT " + ENTRY_COLUMNS + " FROM gl_journal_entries WHERE company_id = ?");
    List<Object> params = new ArrayList<>(List.of(companyId));
    if (from != null) {
      sql.append(" AND posting_date >= ?");
      params.add(from);
    }
    if (to != null) {
      sql.append(" AND posting_date <= ?");
      params.add(to);
    }
    if (sourceType != null) {
      sql.append(" AND source_type = ?");
      params.add(sourceType);
    }
    sql.append(" ORDER BY posting_date DESC, id DESC LIMIT ? OFFSET ?");
    params.add(limit);
    params.add(offset);
    return Jdbc.queryList(uow, sql.toString(), JournalRepository::entry, params.toArray());
  }

  /** Links an entry to its reversal; false if it was already reversed. */
  public boolean setReversedBy(UnitOfWork uow, long id, long reversalId) {
    return Jdbc.update(uow, "UPDATE gl_journal_entries SET reversed_by_id = ? WHERE id = ? AND reversed_by_id IS NULL",
        reversalId, id) == 1;
  }
}

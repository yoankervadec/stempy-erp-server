package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * SQL for the GL reports (docs/ARCHITECTURE.md §22.6), computed from the lines
 * through the (company, account, posting date) index. Amounts are signed
 * debit-positive: debit - credit.
 */
public class ReportRepository {

  /** One account of the chart, as reports show it. */
  public record ChartRow(long id, String accountNo, String name, AccountType type, Long parentId, boolean heading,
      boolean enabled) {
  }

  /** One account's movement: opening (before the range), debits and credits in the range. */
  public record MovementRow(long accountId, BigDecimal opening, BigDecimal debits, BigDecimal credits) {
  }

  /** One line of an account's ledger. */
  public record LedgerRow(long entryId, String entryNo, int lineNo, LocalDate postingDate, String description,
      String sourceType, String sourceNo, BigDecimal debit, BigDecimal credit, Long dimensionSetId) {
  }

  /** Every account of the company, disabled ones included (they may hold balances). */
  public List<ChartRow> chart(UnitOfWork uow, long companyId) {
    return Jdbc.queryList(uow, """
        SELECT id, account_no, name, account_type, parent_id, is_heading, is_enabled
          FROM gl_accounts WHERE company_id = ? AND deleted_at IS NULL ORDER BY account_no""",
        rs -> new ChartRow(rs.getLong(1), rs.getString(2), rs.getString(3), AccountType.valueOf(rs.getString(4)),
            rs.getObject(5, Long.class), rs.getBoolean(6), rs.getBoolean(7)),
        companyId);
  }

  /**
   * Per account with lines up to {@code to}: the balance before {@code from}
   * and the debits and credits from {@code from} to {@code to}.
   *
   * @param valueId null: every line; else only lines whose dimension set holds
   *                this dimension value
   */
  public List<MovementRow> movements(UnitOfWork uow, long companyId, LocalDate from, LocalDate to, Long valueId) {
    List<Object> params = new ArrayList<>(List.of(from, from, from));
    String join = "";
    if (valueId != null) {
      join = " JOIN gl_dimension_set_entries e ON e.set_id = l.dimension_set_id AND e.value_id = ?";
      params.add(valueId);
    }
    params.add(companyId);
    params.add(to);
    return Jdbc.queryList(uow, """
        SELECT l.account_id,
               SUM(CASE WHEN l.posting_date < ? THEN l.debit - l.credit ELSE 0 END),
               SUM(CASE WHEN l.posting_date >= ? THEN l.debit ELSE 0 END),
               SUM(CASE WHEN l.posting_date >= ? THEN l.credit ELSE 0 END)
          FROM gl_journal_lines l""" + join + " " + """
         WHERE l.company_id = ? AND l.posting_date <= ?
         GROUP BY l.account_id""",
        rs -> new MovementRow(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
        params.toArray());
  }

  /** The account's balance before {@code from} (debit - credit). */
  public BigDecimal openingBalance(UnitOfWork uow, long companyId, long accountId, LocalDate from, Long valueId) {
    List<Object> params = new ArrayList<>();
    String join = "";
    if (valueId != null) {
      join = " JOIN gl_dimension_set_entries e ON e.set_id = l.dimension_set_id AND e.value_id = ?";
      params.add(valueId);
    }
    params.add(companyId);
    params.add(accountId);
    params.add(from);
    return Jdbc.queryFirst(uow, "SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM gl_journal_lines l" + join
        + " WHERE l.company_id = ? AND l.account_id = ? AND l.posting_date < ?", rs -> rs.getBigDecimal(1),
        params.toArray()).orElse(BigDecimal.ZERO);
  }

  /** The account's lines from {@code from} to {@code to}, by date, entry and line; at most {@code limit}. */
  public List<LedgerRow> ledger(UnitOfWork uow, long companyId, long accountId, LocalDate from, LocalDate to,
      Long valueId, int limit) {
    List<Object> params = new ArrayList<>();
    String join = "";
    if (valueId != null) {
      join = " JOIN gl_dimension_set_entries e ON e.set_id = l.dimension_set_id AND e.value_id = ?";
      params.add(valueId);
    }
    params.addAll(List.of(companyId, accountId, from, to, limit));
    return Jdbc.queryList(uow, """
        SELECT j.id, j.entry_no, l.line_no, l.posting_date, COALESCE(l.description, j.description), j.source_type,
               j.source_no, l.debit, l.credit, l.dimension_set_id
          FROM gl_journal_lines l JOIN gl_journal_entries j ON j.id = l.entry_id""" + join + " " + """
         WHERE l.company_id = ? AND l.account_id = ? AND l.posting_date BETWEEN ? AND ?
         ORDER BY l.posting_date, j.id, l.line_no
         LIMIT ?""",
        rs -> new LedgerRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getObject(4, LocalDate.class),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
            rs.getObject(10, Long.class)),
        params.toArray());
  }

  /** The company's account with this number (case-insensitive). */
  public Optional<ChartRow> account(UnitOfWork uow, long companyId, String accountNo) {
    return Jdbc.queryFirst(uow, """
        SELECT id, account_no, name, account_type, parent_id, is_heading, is_enabled
          FROM gl_accounts WHERE company_id = ? AND account_no = ? AND deleted_at IS NULL""",
        rs -> new ChartRow(rs.getLong(1), rs.getString(2), rs.getString(3), AccountType.valueOf(rs.getString(4)),
            rs.getObject(5, Long.class), rs.getBoolean(6), rs.getBoolean(7)),
        companyId, accountNo);
  }

  /** The id of a dimension value by codes (case-insensitive), enabled or not: reports cover history. */
  public Optional<Long> dimensionValueId(UnitOfWork uow, long companyId, String dimensionCode,
      String valueCode) {
    return Jdbc.queryFirst(uow, """
        SELECT v.id FROM gl_dimension_values v JOIN gl_dimensions d ON d.id = v.dimension_id
         WHERE d.company_id = ? AND d.code = ? AND v.code = ?""", rs -> rs.getLong(1), companyId, dimensionCode,
        valueCode);
  }

  /** The company's base currency. */
  public String currency(UnitOfWork uow, long companyId) {
    return Jdbc.queryFirst(uow, "SELECT base_currency_code FROM companies WHERE id = ?", rs -> rs.getString(1),
        companyId).orElseThrow(() -> new IllegalStateException("company " + companyId + " not found"));
  }
}

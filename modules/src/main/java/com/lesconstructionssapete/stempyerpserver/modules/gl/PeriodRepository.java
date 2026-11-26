package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** SQL for gl_fiscal_years and gl_periods. */
public class PeriodRepository {

  private static final String PERIOD_COLUMNS = """
      id, company_id, fiscal_year_id, period_no, start_date, end_date, status, version, status_changed_at,
      status_changed_by""";

  private static StoredPeriod period(ResultSet rs) throws SQLException {
    return new StoredPeriod(rs.getLong("id"), rs.getLong("company_id"), rs.getLong("fiscal_year_id"),
        rs.getInt("period_no"), rs.getObject("start_date", LocalDate.class),
        rs.getObject("end_date", LocalDate.class), PeriodStatus.valueOf(rs.getString("status")),
        rs.getInt("version"), Timestamps.read(rs, "status_changed_at"),
        rs.getObject("status_changed_by", Long.class));
  }

  private static StoredFiscalYear year(ResultSet rs) throws SQLException {
    return new StoredFiscalYear(rs.getLong("id"), rs.getLong("company_id"),
        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
        Timestamps.read(rs, "created_at"), rs.getLong("created_by"));
  }

  /**
   * Locks the company row until the transaction ends: fiscal years of one
   * company are created one at a time, so two first years can't both start.
   */
  public boolean lockCompany(UnitOfWork uow, long companyId) {
    return Jdbc.queryFirst(uow, "SELECT id FROM companies WHERE id = ? FOR UPDATE", rs -> true, companyId)
        .isPresent();
  }

  /** The company's latest fiscal year. */
  public Optional<StoredFiscalYear> lastFiscalYear(UnitOfWork uow, long companyId) {
    return Jdbc.queryFirst(uow, """
        SELECT id, company_id, start_date, end_date, created_at, created_by
          FROM gl_fiscal_years WHERE company_id = ? ORDER BY start_date DESC LIMIT 1""",
        PeriodRepository::year, companyId);
  }

  public List<StoredFiscalYear> fiscalYears(UnitOfWork uow, long companyId) {
    return Jdbc.queryList(uow, """
        SELECT id, company_id, start_date, end_date, created_at, created_by
          FROM gl_fiscal_years WHERE company_id = ? ORDER BY start_date""", PeriodRepository::year, companyId);
  }

  public long insertFiscalYear(UnitOfWork uow, long companyId, LocalDate start, LocalDate end, long userId,
      Instant now) {
    return Jdbc.insert(uow, """
        INSERT INTO gl_fiscal_years (company_id, start_date, end_date, created_at, created_by)
        VALUES (?, ?, ?, ?, ?)""", companyId, start, end, now, userId);
  }

  public void insertPeriod(UnitOfWork uow, long companyId, long fiscalYearId, int periodNo, LocalDate start,
      LocalDate end) {
    Jdbc.insert(uow, """
        INSERT INTO gl_periods (company_id, fiscal_year_id, period_no, start_date, end_date)
        VALUES (?, ?, ?, ?, ?)""", companyId, fiscalYearId, periodNo, start, end);
  }

  /** Every period of the company, by date. */
  public List<StoredPeriod> periods(UnitOfWork uow, long companyId) {
    return Jdbc.queryList(uow, "SELECT " + PERIOD_COLUMNS + " FROM gl_periods WHERE company_id = ? ORDER BY start_date",
        PeriodRepository::period, companyId);
  }

  public Optional<StoredPeriod> findPeriod(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + PERIOD_COLUMNS + " FROM gl_periods WHERE id = ?",
        PeriodRepository::period, id);
  }

  /**
   * Locks the period until the transaction ends. Posting reads it FOR SHARE,
   * so a close waits for entries being posted into it, and the reverse.
   */
  public Optional<StoredPeriod> findPeriodForUpdate(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + PERIOD_COLUMNS + " FROM gl_periods WHERE id = ? FOR UPDATE",
        PeriodRepository::period, id);
  }

  public void setStatus(UnitOfWork uow, long id, PeriodStatus status, long userId, Instant now) {
    Jdbc.update(uow, """
        UPDATE gl_periods SET status = ?, version = version + 1, status_changed_at = ?, status_changed_by = ?
         WHERE id = ?""", status, now, userId, id);
  }

  /**
   * The company's period containing {@code date}, read with a shared lock
   * (FOR SHARE) until the transaction ends: a close (FOR UPDATE) waits for the
   * entries being posted into it, and a posting never lands in a period that
   * closed under it.
   */
  public Optional<StoredPeriod> findPeriodForPosting(UnitOfWork uow, long companyId, LocalDate date) {
    return Jdbc.queryFirst(uow, "SELECT " + PERIOD_COLUMNS
        + " FROM gl_periods WHERE company_id = ? AND start_date <= ? AND end_date >= ? FOR SHARE",
        PeriodRepository::period, companyId, date, date);
  }
}

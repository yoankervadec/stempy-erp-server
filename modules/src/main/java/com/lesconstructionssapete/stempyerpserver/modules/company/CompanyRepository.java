package com.lesconstructionssapete.stempyerpserver.modules.company;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** What the company module reads beyond the generic routes: companies, currencies, exchange_rates. No rules. */
public class CompanyRepository {

  /** companies.base_currency_code, if the company exists. */
  public Optional<String> findBaseCurrencyCode(UnitOfWork uow, long companyId) {
    return Jdbc.queryFirst(uow, "SELECT base_currency_code FROM companies WHERE id = ?",
        rs -> rs.getString(1), companyId);
  }

  /** The code of a live currency. */
  public Optional<String> findCurrencyCode(UnitOfWork uow, long currencyId) {
    return Jdbc.queryFirst(uow, "SELECT code FROM currencies WHERE id = ? AND deleted_at IS NULL",
        rs -> rs.getString(1), currencyId);
  }

  /** Whether a live rate of the currency starts on the date, other than {@code excludeId} (null: none). */
  public boolean rateStartsOn(UnitOfWork uow, long companyId, long currencyId, LocalDate date, Long excludeId) {
    return Jdbc.queryFirst(uow, """
        SELECT 1 FROM exchange_rates
        WHERE company_id = ? AND currency_id = ? AND starting_date = ? AND deleted_at IS NULL
          AND id <> ?""", rs -> true, companyId, currencyId, date, excludeId == null ? 0L : excludeId)
        .isPresent();
  }

  /** The live rate of the currency that applies on the date: the latest starting on or before it. */
  public Optional<BigDecimal> findRate(UnitOfWork uow, long companyId, String currencyCode, LocalDate date) {
    return Jdbc.queryFirst(uow, """
        SELECT r.rate FROM exchange_rates r JOIN currencies c ON c.id = r.currency_id
        WHERE r.company_id = ? AND c.code = ? AND r.starting_date <= ? AND r.deleted_at IS NULL
        ORDER BY r.starting_date DESC LIMIT 1""", rs -> rs.getBigDecimal(1), companyId, currencyCode, date);
  }
}

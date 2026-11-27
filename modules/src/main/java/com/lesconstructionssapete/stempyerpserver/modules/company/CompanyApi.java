package com.lesconstructionssapete.stempyerpserver.modules.company;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/**
 * Public API of the company module, published in the ServiceRegistry
 * (docs/ARCHITECTURE.md §25.4): the company's base currency and what an amount
 * in another currency is worth in it.
 *
 * <pre>
 * Money cad = company.toBaseCurrency(uow, rc.companyId(), Money.of("100.00", "USD"), invoiceDate).rounded();
 * </pre>
 *
 * Reads in the caller's transaction; no permission is checked (the calling
 * module checked its own).
 */
public interface CompanyApi {

  /**
   * companies.base_currency_code as a Currency.
   *
   * @throws IllegalStateException for an unknown company or a code that isn't ISO 4217
   */
  Currency baseCurrency(UnitOfWork uow, long companyId);

  /**
   * The id of the base currency's row in currencies, for a field that points
   * at a currency (a new customer's or product's currencyId). Empty when the
   * company is unknown or its base currency has no live currencies row.
   */
  Optional<Long> baseCurrencyId(UnitOfWork uow, long companyId);

  /**
   * Units of the base currency one unit of {@code currency} is worth on
   * {@code date}: 1 for the base currency itself, else the rate of the latest
   * exchange rate starting on or before the date.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException
   *         EXCHANGE_RATE_NOT_FOUND (params currency, date) when the company has none
   */
  BigDecimal exchangeRate(UnitOfWork uow, long companyId, Currency currency, LocalDate date);

  /**
   * {@code amount} in the base currency at {@code date}'s rate, exact: call
   * Money.rounded() when it becomes a real amount. The same amount when it is
   * already in the base currency.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException
   *         EXCHANGE_RATE_NOT_FOUND, as {@link #exchangeRate}
   */
  Money toBaseCurrency(UnitOfWork uow, long companyId, Money amount, LocalDate date);
}

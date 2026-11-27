package com.lesconstructionssapete.stempyerpserver.modules.company;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.currencies.Currency;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * What one unit of a currency is worth in the company's base currency, from
 * a starting date until the next rate of that currency starts
 * (exchange_rates, docs/ARCHITECTURE.md §25.3). 1 USD = 1.365 CAD is
 * {currency: USD, rate: 1.365}.
 *
 * Written by the generic entity routes; ExchangeRateService adds the rules
 * the fields can't say: never the base currency, one rate per currency and
 * starting date.
 */
public class ExchangeRate extends StandardEntity {

  /** exchange_rates.rate is DECIMAL(20, 10). */
  static final int RATE_SCALE = 10;

  public static final EntityDefinition<ExchangeRate> DEF = Entities.define("exchangeRate", "exchange_rates",
      ExchangeRate::new);

  /** Fixed once created: a rate of another currency is another rate. */
  public static final Field<ExchangeRate, Long> CURRENCY_ID = DEF.integer(
      "currencyId", "currency_id", ExchangeRate::currencyId, ExchangeRate::setCurrencyId)
      .required().immutable().filterable();

  public static final Field<ExchangeRate, LocalDate> STARTING_DATE = DEF.date(
      "startingDate", "starting_date", ExchangeRate::startingDate, ExchangeRate::setStartingDate)
      .required().filterable().sortable();

  /** Units of the base currency for one unit of the currency; above zero. */
  public static final Field<ExchangeRate, BigDecimal> RATE = DEF.decimal(
      "rate", "rate", ExchangeRate::rate, ExchangeRate::setRate)
      .required().min("0.0000000001").filterable().sortable();

  /** Filters and sorts like "currency.code". */
  public static final Relation<ExchangeRate, Currency> CURRENCY_REL = DEF.manyToOne(
      "currency", () -> Currency.DEF, "currency_id");

  /** UI hints (docs/ARCHITECTURE.md §23.4). */
  static {
    RATE.scale(RATE_SCALE);
    DEF.titleField(STARTING_DATE)
        .listColumns(CURRENCY_ID, STARTING_DATE, RATE)
        .defaultSort(SortKey.desc(STARTING_DATE));
  }

  private Long currencyId;
  private LocalDate startingDate;
  private BigDecimal rate;

  public Long currencyId() {
    return currencyId;
  }

  public void setCurrencyId(Long currencyId) {
    this.currencyId = currencyId;
  }

  public LocalDate startingDate() {
    return startingDate;
  }

  public void setStartingDate(LocalDate startingDate) {
    this.startingDate = startingDate;
  }

  public BigDecimal rate() {
    return rate;
  }

  public void setRate(BigDecimal rate) {
    this.rate = rate;
  }
}

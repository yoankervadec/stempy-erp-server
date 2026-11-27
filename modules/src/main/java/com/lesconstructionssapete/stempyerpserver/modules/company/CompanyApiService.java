package com.lesconstructionssapete.stempyerpserver.modules.company;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindBaseCurrencyOperation;
import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindExchangeRateOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/** Implements CompanyApi (orchestrator): runs in the caller's transaction. */
public class CompanyApiService implements CompanyApi {

  private final FindBaseCurrencyOperation findBaseCurrency;
  private final FindExchangeRateOperation findRate;

  public CompanyApiService(FindBaseCurrencyOperation findBaseCurrency, FindExchangeRateOperation findRate) {
    this.findBaseCurrency = findBaseCurrency;
    this.findRate = findRate;
  }

  @Override
  public Currency baseCurrency(UnitOfWork uow, long companyId) {
    String code = findBaseCurrency.execute(uow, companyId)
        .orElseThrow(() -> new IllegalStateException("No company " + companyId));
    try {
      return Money.currency(code);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Company " + companyId + " has base currency " + code
          + ", which isn't an ISO 4217 code", e);
    }
  }

  @Override
  public BigDecimal exchangeRate(UnitOfWork uow, long companyId, Currency currency, LocalDate date) {
    if (currency.equals(baseCurrency(uow, companyId))) {
      return BigDecimal.ONE;
    }
    return findRate.execute(uow, new FindExchangeRateOperation.Input(companyId, currency.getCurrencyCode(), date))
        .orElseThrow(() -> (ConflictException) new ConflictException("EXCHANGE_RATE_NOT_FOUND",
            "No exchange rate of " + currency.getCurrencyCode() + " on or before " + date)
            .param("currency", currency.getCurrencyCode()).param("date", date));
  }

  @Override
  public Money toBaseCurrency(UnitOfWork uow, long companyId, Money amount, LocalDate date) {
    Currency base = baseCurrency(uow, companyId);
    if (amount.currency().equals(base)) {
      return amount;
    }
    BigDecimal rate = exchangeRate(uow, companyId, amount.currency(), date);
    return Money.of(amount.amount().multiply(rate), base);
  }
}

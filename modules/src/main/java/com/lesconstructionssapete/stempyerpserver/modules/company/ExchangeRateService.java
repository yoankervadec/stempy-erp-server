package com.lesconstructionssapete.stempyerpserver.modules.company;

import java.time.LocalDate;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.company.operations.CheckRateDateTakenOperation;
import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindBaseCurrencyOperation;
import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindCurrencyCodeOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordRules;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * The exchange rates' RecordRules (docs/ARCHITECTURE.md §25.3), enforced by
 * the generic create and update:
 * <ul>
 * <li>BASE_CURRENCY on currencyId: the base currency has no rate (always 1).</li>
 * <li>RATE_EXISTS on startingDate: another live rate of the currency starts on
 * that date. (exchange_rates' unique key catches a concurrent one: 409
 * DUPLICATE_VALUE.)</li>
 * </ul>
 * An unknown currency is left to the generic reference check (INVALID_REFERENCE).
 */
public class ExchangeRateService implements RecordRules {

  private final FindBaseCurrencyOperation findBaseCurrency;
  private final FindCurrencyCodeOperation findCurrencyCode;
  private final CheckRateDateTakenOperation dateTaken;

  public ExchangeRateService(FindBaseCurrencyOperation findBaseCurrency, FindCurrencyCodeOperation findCurrencyCode,
      CheckRateDateTakenOperation dateTaken) {
    this.findBaseCurrency = findBaseCurrency;
    this.findCurrencyCode = findCurrencyCode;
    this.dateTaken = dateTaken;
  }

  @Override
  public void validateWrite(UnitOfWork uow, RequestContext rc, Map<String, Object> record, Validation v) {
    if (!(record.get(ExchangeRate.CURRENCY_ID.name()) instanceof Long currencyId)) {
      return;
    }
    String code = findCurrencyCode.execute(uow, currencyId).orElse(null);
    if (code == null) {
      return;
    }
    if (findBaseCurrency.execute(uow, rc.companyId()).filter(code::equals).isPresent()) {
      v.add(ExchangeRate.CURRENCY_ID.name(), "BASE_CURRENCY",
          "is the company's base currency (" + code + "): its rate is always 1", Validation.params("currency", code));
      return;
    }
    Long id = record.get(ExchangeRate.DEF.id().name()) instanceof Long l ? l : null;
    if (record.get(ExchangeRate.STARTING_DATE.name()) instanceof LocalDate date
        && dateTaken.execute(uow, new CheckRateDateTakenOperation.Input(rc.companyId(), currencyId, date, id))) {
      v.add(ExchangeRate.STARTING_DATE.name(), "RATE_EXISTS",
          "a rate of " + code + " already starts on " + date, Validation.params("currency", code, "date", date));
    }
  }
}

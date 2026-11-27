package com.lesconstructionssapete.stempyerpserver.modules.company.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.company.CompanyRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** The company's rate of a currency on a date: the latest one starting on or before it. */
public class FindExchangeRateOperation implements Operation<FindExchangeRateOperation.Input, Optional<BigDecimal>> {

  public record Input(long companyId, String currencyCode, LocalDate date) {
  }

  private final CompanyRepository companies;

  public FindExchangeRateOperation(CompanyRepository companies) {
    this.companies = companies;
  }

  @Override
  public Optional<BigDecimal> execute(UnitOfWork uow, Input in) {
    return companies.findRate(uow, in.companyId(), in.currencyCode(), in.date());
  }
}

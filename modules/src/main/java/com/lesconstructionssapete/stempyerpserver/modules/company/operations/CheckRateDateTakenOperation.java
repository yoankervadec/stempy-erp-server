package com.lesconstructionssapete.stempyerpserver.modules.company.operations;

import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.company.CompanyRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Whether another live rate of the currency already starts on the date, in the company. */
public class CheckRateDateTakenOperation implements Operation<CheckRateDateTakenOperation.Input, Boolean> {

  /** @param excludeId the rate being changed, null for a new one */
  public record Input(long companyId, long currencyId, LocalDate startingDate, Long excludeId) {
  }

  private final CompanyRepository companies;

  public CheckRateDateTakenOperation(CompanyRepository companies) {
    this.companies = companies;
  }

  @Override
  public Boolean execute(UnitOfWork uow, Input in) {
    return companies.rateStartsOn(uow, in.companyId(), in.currencyId(), in.startingDate(), in.excludeId());
  }
}

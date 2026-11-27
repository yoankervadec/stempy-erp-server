package com.lesconstructionssapete.stempyerpserver.modules.company.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.company.CompanyRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** The code of a live currency (currencies are install-wide); empty if there is none. Input: its id. */
public class FindCurrencyCodeOperation implements Operation<Long, Optional<String>> {

  private final CompanyRepository companies;

  public FindCurrencyCodeOperation(CompanyRepository companies) {
    this.companies = companies;
  }

  @Override
  public Optional<String> execute(UnitOfWork uow, Long currencyId) {
    return companies.findCurrencyCode(uow, currencyId);
  }
}

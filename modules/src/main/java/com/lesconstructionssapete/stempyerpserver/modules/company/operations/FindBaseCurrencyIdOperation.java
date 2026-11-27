package com.lesconstructionssapete.stempyerpserver.modules.company.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.company.CompanyRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** The id of the company's base currency in currencies; empty if there is no such row. Input: the company id. */
public class FindBaseCurrencyIdOperation implements Operation<Long, Optional<Long>> {

  private final CompanyRepository companies;

  public FindBaseCurrencyIdOperation(CompanyRepository companies) {
    this.companies = companies;
  }

  @Override
  public Optional<Long> execute(UnitOfWork uow, Long companyId) {
    return companies.findBaseCurrencyId(uow, companyId);
  }
}

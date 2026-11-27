package com.lesconstructionssapete.stempyerpserver.modules.company.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.company.CompanyRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** The company's base currency code (CAD); empty for an unknown company. Input: the company id. */
public class FindBaseCurrencyOperation implements Operation<Long, Optional<String>> {

  private final CompanyRepository companies;

  public FindBaseCurrencyOperation(CompanyRepository companies) {
    this.companies = companies;
  }

  @Override
  public Optional<String> execute(UnitOfWork uow, Long companyId) {
    return companies.findBaseCurrencyCode(uow, companyId);
  }
}

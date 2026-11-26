package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.ItemRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Input: the company id. Output: the id of its base currency's currencies row, if any. */
public class FindBaseCurrencyOperation implements Operation<Long, Optional<Long>> {

  private final ItemRepository repository;

  public FindBaseCurrencyOperation(ItemRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<Long> execute(UnitOfWork uow, Long companyId) {
    return repository.findBaseCurrencyId(uow, companyId);
  }
}

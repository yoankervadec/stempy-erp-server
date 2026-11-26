package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredAccount;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** One account of a company by id; empty when it belongs to another company. */
public class FindAccountOperation implements Operation<FindAccountOperation.Input, Optional<StoredAccount>> {

  public record Input(long companyId, long id) {
  }

  private final AccountRepository repository;

  public FindAccountOperation(AccountRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredAccount> execute(UnitOfWork uow, Input in) {
    return repository.find(uow, in.companyId(), in.id());
  }
}

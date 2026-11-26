package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredAccount;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** One account by id, whatever its company: the orchestrator checks visibility. */
public class FindAccountOperation implements Operation<Long, Optional<StoredAccount>> {

  private final AccountRepository repository;

  public FindAccountOperation(AccountRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredAccount> execute(UnitOfWork uow, Long id) {
    return repository.find(uow, id);
  }
}

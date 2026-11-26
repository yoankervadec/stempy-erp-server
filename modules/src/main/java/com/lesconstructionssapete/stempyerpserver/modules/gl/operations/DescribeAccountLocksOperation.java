package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * What an account's history freezes (docs/ARCHITECTURE.md §22.2): postings
 * fix its type and heading flag, sub-accounts keep it a heading. Input: the
 * account id.
 */
public class DescribeAccountLocksOperation implements Operation<Long, DescribeAccountLocksOperation.Locks> {

  public record Locks(boolean hasPostings, boolean hasChildren) {
  }

  private final AccountRepository repository;

  public DescribeAccountLocksOperation(AccountRepository repository) {
    this.repository = repository;
  }

  @Override
  public Locks execute(UnitOfWork uow, Long accountId) {
    return new Locks(repository.hasPostings(uow, accountId), repository.hasChildren(uow, accountId));
  }
}

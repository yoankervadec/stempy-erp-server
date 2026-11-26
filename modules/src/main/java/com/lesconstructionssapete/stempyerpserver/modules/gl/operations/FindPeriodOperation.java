package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredPeriod;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** One period of a company by id; empty when it belongs to another company. */
public class FindPeriodOperation implements Operation<FindPeriodOperation.Input, Optional<StoredPeriod>> {

  public record Input(long companyId, long id) {
  }

  private final PeriodRepository repository;

  public FindPeriodOperation(PeriodRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredPeriod> execute(UnitOfWork uow, Input in) {
    return repository.findPeriod(uow, in.companyId(), in.id());
  }
}

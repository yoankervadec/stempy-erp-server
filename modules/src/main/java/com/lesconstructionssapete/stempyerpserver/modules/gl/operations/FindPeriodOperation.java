package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredPeriod;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** One period by id, whatever its company: the orchestrator checks visibility. */
public class FindPeriodOperation implements Operation<Long, Optional<StoredPeriod>> {

  private final PeriodRepository repository;

  public FindPeriodOperation(PeriodRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredPeriod> execute(UnitOfWork uow, Long id) {
    return repository.findPeriod(uow, id);
  }
}

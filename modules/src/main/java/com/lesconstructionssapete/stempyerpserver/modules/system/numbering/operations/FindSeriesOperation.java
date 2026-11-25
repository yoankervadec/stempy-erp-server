package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberSeriesAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.StoredSeries;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** One series by id, whatever its company: the orchestrator checks visibility. */
public class FindSeriesOperation implements Operation<Long, Optional<StoredSeries>> {

  private final NumberSeriesAdminRepository repository;

  public FindSeriesOperation(NumberSeriesAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredSeries> execute(UnitOfWork uow, Long id) {
    return repository.find(uow, id);
  }
}

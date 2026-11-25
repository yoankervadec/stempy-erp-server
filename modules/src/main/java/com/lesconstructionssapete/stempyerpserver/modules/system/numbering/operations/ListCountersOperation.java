package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberSeriesAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.StoredCounter;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** A series' counters, by scope. */
public class ListCountersOperation implements Operation<Long, List<StoredCounter>> {

  private final NumberSeriesAdminRepository repository;

  public ListCountersOperation(NumberSeriesAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<StoredCounter> execute(UnitOfWork uow, Long seriesId) {
    return repository.counters(uow, seriesId);
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository.SetEntry;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** A set's (dimension code, value code) pairs, by dimension code. */
public class DescribeDimensionSetOperation implements Operation<Long, List<SetEntry>> {

  private final DimensionRepository repository;

  public DescribeDimensionSetOperation(DimensionRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<SetEntry> execute(UnitOfWork uow, Long setId) {
    return repository.setEntries(uow, setId);
  }
}

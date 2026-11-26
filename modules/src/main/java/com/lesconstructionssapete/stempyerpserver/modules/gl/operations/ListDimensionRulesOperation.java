package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository.RuleRow;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** An account's dimension rules, by dimension code. */
public class ListDimensionRulesOperation implements Operation<Long, List<RuleRow>> {

  private final DimensionRepository repository;

  public ListDimensionRulesOperation(DimensionRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<RuleRow> execute(UnitOfWork uow, Long accountId) {
    return repository.rules(uow, accountId);
  }
}

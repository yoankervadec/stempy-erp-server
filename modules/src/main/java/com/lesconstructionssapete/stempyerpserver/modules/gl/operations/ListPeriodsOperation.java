package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredFiscalYear;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredPeriod;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** A company's fiscal years and all their periods, by date. */
public class ListPeriodsOperation implements Operation<Long, ListPeriodsOperation.Output> {

  public record Output(List<StoredFiscalYear> years, List<StoredPeriod> periods) {
  }

  private final PeriodRepository repository;

  public ListPeriodsOperation(PeriodRepository repository) {
    this.repository = repository;
  }

  @Override
  public Output execute(UnitOfWork uow, Long companyId) {
    return new Output(repository.fiscalYears(uow, companyId), repository.periods(uow, companyId));
  }
}

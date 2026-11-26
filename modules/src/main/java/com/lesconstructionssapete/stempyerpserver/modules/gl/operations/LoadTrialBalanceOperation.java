package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository.ChartRow;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository.MovementRow;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * What a trial balance needs, in one read-only transaction (one consistent
 * snapshot): the chart, every account's movement, the currency, and the
 * dimension value to filter on, if any.
 */
public class LoadTrialBalanceOperation implements Operation<LoadTrialBalanceOperation.Input,
    LoadTrialBalanceOperation.Output> {

  /**
   * @param dimensionCode null: no filter
   * @param valueCode     null when dimensionCode is
   */
  public record Input(long companyId, LocalDate from, LocalDate to, String dimensionCode, String valueCode) {
  }

  /** @param valueFound false when a filter was asked for and no such value exists */
  public record Output(String currency, List<ChartRow> chart, List<MovementRow> movements, boolean valueFound) {
  }

  private final ReportRepository repository;

  public LoadTrialBalanceOperation(ReportRepository repository) {
    this.repository = repository;
  }

  @Override
  public Output execute(UnitOfWork uow, Input in) {
    String currency = repository.currency(uow, in.companyId());
    Long valueId = null;
    if (in.dimensionCode() != null) {
      Optional<Long> found = repository.dimensionValueId(uow, in.companyId(), in.dimensionCode(), in.valueCode());
      if (found.isEmpty()) {
        return new Output(currency, List.of(), List.of(), false);
      }
      valueId = found.get();
    }
    return new Output(currency, repository.chart(uow, in.companyId()),
        repository.movements(uow, in.companyId(), in.from(), in.to(), valueId), true);
  }
}

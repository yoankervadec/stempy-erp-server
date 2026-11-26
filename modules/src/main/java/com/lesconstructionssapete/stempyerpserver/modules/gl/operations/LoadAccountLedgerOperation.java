package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository.ChartRow;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository.LedgerRow;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * What an account ledger needs, in one read-only transaction: the account,
 * its balance before the range, its lines in the range (at most
 * {@code limit}), the currency, and the dimension value to filter on.
 */
public class LoadAccountLedgerOperation implements Operation<LoadAccountLedgerOperation.Input,
    LoadAccountLedgerOperation.Output> {

  public record Input(long companyId, String accountNo, LocalDate from, LocalDate to, String dimensionCode,
      String valueCode, int limit) {
  }

  /**
   * @param account    empty: no such account (nothing else is loaded)
   * @param valueFound false when a filter was asked for and no such value
   *                   exists (nothing else is loaded)
   */
  public record Output(String currency, Optional<ChartRow> account, boolean valueFound, BigDecimal opening,
      List<LedgerRow> rows) {
  }

  private final ReportRepository repository;

  public LoadAccountLedgerOperation(ReportRepository repository) {
    this.repository = repository;
  }

  @Override
  public Output execute(UnitOfWork uow, Input in) {
    String currency = repository.currency(uow, in.companyId());
    Optional<ChartRow> account = repository.account(uow, in.companyId(), in.accountNo());
    Long valueId = null;
    if (in.dimensionCode() != null) {
      Optional<Long> found = repository.dimensionValueId(uow, in.companyId(), in.dimensionCode(), in.valueCode());
      if (found.isEmpty()) {
        return new Output(currency, account, false, BigDecimal.ZERO, List.of());
      }
      valueId = found.get();
    }
    if (account.isEmpty() || account.get().heading()) {
      return new Output(currency, account, true, BigDecimal.ZERO, List.of());
    }
    long id = account.get().id();
    return new Output(currency, account, true,
        repository.openingBalance(uow, in.companyId(), id, in.from(), valueId),
        repository.ledger(uow, in.companyId(), id, in.from(), in.to(), valueId, in.limit()));
  }
}

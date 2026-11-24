package com.lesconstructionssapete.stempyerpserver.modules.tax;

import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.tax.operations.LoadTaxRuleSetOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * TaxApi: reads the tax data with the caller's transaction, then calculates.
 * Opens no transaction of its own, so a document computes its taxes in the
 * same transaction that saves them.
 */
public class TaxService implements TaxApi {

  private final LoadTaxRuleSetOperation loadRules;
  private final TaxCalculator calculator;

  public TaxService(LoadTaxRuleSetOperation loadRules, TaxCalculator calculator) {
    this.loadRules = loadRules;
    this.calculator = calculator;
  }

  @Override
  public TaxResult calculate(UnitOfWork uow, TaxRequest request) {
    Objects.requireNonNull(uow, "uow");
    Objects.requireNonNull(request, "request");
    return calculator.calculate(request, loadRules.execute(uow, null));
  }
}

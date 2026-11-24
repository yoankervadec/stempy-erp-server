package com.lesconstructionssapete.stempyerpserver.modules.tax.operations;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRepository;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * Loads the enabled tax data into a TaxRuleSet. The tables are small (a few
 * hundred rows at most), so everything is read; the rule set picks what is
 * valid on the document date. Input: none.
 */
public class LoadTaxRuleSetOperation implements Operation<Void, TaxRuleSet> {

  private final TaxRepository taxes;

  public LoadTaxRuleSetOperation(TaxRepository taxes) {
    this.taxes = taxes;
  }

  @Override
  public TaxRuleSet execute(UnitOfWork uow, Void input) {
    return new TaxRuleSet(taxes.jurisdictionCodes(uow), taxes.categoryCodes(uow), taxes.groupCodes(uow),
        taxes.rates(uow), taxes.groupLines(uow), taxes.rules(uow));
  }
}

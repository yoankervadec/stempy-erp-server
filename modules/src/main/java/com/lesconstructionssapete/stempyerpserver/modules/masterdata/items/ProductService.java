package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.operations.FindBaseCurrencyOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordRules;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * The products' RecordRules (docs/ARCHITECTURE.md §23.9): a new product is
 * priced in the company's base currency. Products are otherwise plain
 * records, written by the generic routes.
 */
public class ProductService implements RecordRules {

  private final FindBaseCurrencyOperation findBaseCurrency;

  public ProductService(FindBaseCurrencyOperation findBaseCurrency) {
    this.findBaseCurrency = findBaseCurrency;
  }

  @Override
  public Map<String, Object> defaults(UnitOfWork uow, RequestContext rc) {
    return findBaseCurrency.execute(uow, rc.companyId())
        .<Map<String, Object>>map(id -> Map.of(Product.CURRENCY_ID.name(), id))
        .orElse(Map.of());
  }
}

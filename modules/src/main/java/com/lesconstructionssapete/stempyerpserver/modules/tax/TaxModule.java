package com.lesconstructionssapete.stempyerpserver.modules.tax;

import com.lesconstructionssapete.stempyerpserver.modules.tax.operations.LoadTaxRuleSetOperation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;

/**
 * Sales taxes (docs/ARCHITECTURE.md §19): publishes TaxApi. No routes and no
 * entities yet; the tax data is maintained with migrations (V7) or SQL.
 */
public class TaxModule implements ERPModule {

  @Override
  public String name() {
    return "tax";
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    // none yet: rates, groups and rules have no admin API
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
    serviceRegistry.register(TaxApi.class,
        new TaxService(new LoadTaxRuleSetOperation(new TaxRepository()), new TaxCalculator()));
  }
}

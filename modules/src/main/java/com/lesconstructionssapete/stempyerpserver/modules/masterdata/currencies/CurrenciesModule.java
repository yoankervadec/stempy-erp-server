package com.lesconstructionssapete.stempyerpserver.modules.masterdata.currencies;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordLayout;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Master data: currencies (docs/ARCHITECTURE.md §24). Install-wide records
 * with no rules beyond their field declarations, served by the generic
 * EntityEndpoints:
 *
 * <pre>
 * QUERY /masterdata/currencies   GET / PATCH /masterdata/currencies/{id}   POST
 * </pre>
 *
 * No DELETE: a currency is disabled instead. The list is shared by every
 * company, so a change made with one company's permission is seen by all.
 *
 * Depends on the users module (created_by / updated_by relations); must come
 * before masterdata.items, whose products point at currencies.
 */
public class CurrenciesModule implements ERPModule {

  static final String PERMISSION = "masterdata.currency";

  @Override
  public String name() {
    return "masterdata.currencies";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    // No delete permission: these records are disabled, never deleted.
    permissions
        .declare(PERMISSION + ".read", "View currencies")
        .declare(PERMISSION + ".create", "Create currencies")
        .declare(PERMISSION + ".update", "Change currencies");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(Currency.class);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityEndpoints crud = serviceRegistry.get(EntityEndpoints.class);
    crud.register(routes, "/masterdata/currencies", Currency.DEF, PERMISSION,
        Action.READ, Action.CREATE, Action.UPDATE);
    crud.layout(Currency.DEF, RecordLayout.of(Currency.DEF)
        .section("general", 2, Currency.CODE, Currency.NAME, Currency.IS_ENABLED)
        .tab(RecordLayout.HISTORY));
  }
}

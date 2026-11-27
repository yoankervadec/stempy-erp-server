package com.lesconstructionssapete.stempyerpserver.modules.masterdata.units;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordLayout;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Master data: units of measure (docs/ARCHITECTURE.md §24). Install-wide
 * records with no rules beyond their field declarations, served by the
 * generic EntityEndpoints:
 *
 * <pre>
 * QUERY /masterdata/units-of-measure   GET / PATCH /masterdata/units-of-measure/{id}   POST
 * </pre>
 *
 * No DELETE: a unit is disabled instead. The list is shared by every company,
 * so a change made with one company's permission is seen by all.
 *
 * Depends on the users module (created_by / updated_by relations); must come
 * before masterdata.items, whose products point at units.
 */
public class UnitsModule implements ERPModule {

  static final String PERMISSION = "masterdata.unitOfMeasure";

  @Override
  public String name() {
    return "masterdata.units";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    // No delete permission: these records are disabled, never deleted.
    permissions
        .declare(PERMISSION + ".read", "View units of measure")
        .declare(PERMISSION + ".create", "Create units of measure")
        .declare(PERMISSION + ".update", "Change units of measure");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(UnitOfMeasure.class);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityEndpoints crud = serviceRegistry.get(EntityEndpoints.class);
    crud.register(routes, "/masterdata/units-of-measure", UnitOfMeasure.DEF, PERMISSION,
        Action.READ, Action.CREATE, Action.UPDATE);
    crud.layout(UnitOfMeasure.DEF, RecordLayout.of(UnitOfMeasure.DEF)
        .section("general", 2, UnitOfMeasure.CODE, UnitOfMeasure.DESCRIPTION, UnitOfMeasure.IS_ENABLED)
        .tab(RecordLayout.HISTORY));
  }
}

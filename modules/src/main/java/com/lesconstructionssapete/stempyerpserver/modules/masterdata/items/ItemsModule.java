package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityQueryEndpoint;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Master data: products and product categories.
 *
 * Depends on the users module (created_by / updated_by relations), so it must
 * come after it in AllModules.
 */
public class ItemsModule implements ERPModule {

  @Override
  public String name() {
    return "masterdata.items";
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(
        Product.class, ProductCategory.class);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityQueryEndpoint queries = serviceRegistry.get(EntityQueryEndpoint.class);

    // TODO check a read permission (e.g. "masterdata.products.read") once
    // PermissionChecker exists.
    routes.query("/masterdata/products", queries.handler(Product.DEF));
    routes.query("/masterdata/product-categories", queries.handler(ProductCategory.DEF));
  }

}

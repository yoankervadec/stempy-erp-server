package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityQueryEndpoint;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Master data: products and product categories.
 *
 * Depends on the users module (created_by / updated_by relations), so it must
 * be listed after it in Main.
 */
public class ItemService implements ERPModule {

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
  public void registerServices(ServiceRegistry serviceRegistry) {
    // No public API yet.
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityQueryEndpoint queries = serviceRegistry.get(EntityQueryEndpoint.class);

    // TODO check a read permission (e.g. "masterdata.products.read") once
    // PermissionChecker exists.
    routes.query("/masterdata/products", queries.handler(Product.DEF));
    routes.query("/masterdata/product-categories", queries.handler(ProductCategory.DEF));
  }

  @Override
  public void registerEventHandlers(EventBus eventBus, ServiceRegistry serviceRegistry) {
    // Nothing to listen to yet.
  }

}

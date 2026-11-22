package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
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
  public void registerPermissions(PermissionRegistry permissions) {
    permissions
        .declare("masterdata.product.read", "View products")
        .declare("masterdata.productCategory.read", "View product categories");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(
        Product.class, ProductCategory.class);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityQueryEndpoint queries = serviceRegistry.get(EntityQueryEndpoint.class);

    routes.query("/masterdata/products", queries.handler(Product.DEF, "masterdata.product.read"));
    routes.query("/masterdata/product-categories",
        queries.handler(ProductCategory.DEF, "masterdata.productCategory.read"));
  }

}

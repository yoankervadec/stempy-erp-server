package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Master data: products and product categories. Plain records with no
 * business rules beyond their field declarations, so the generic
 * EntityEndpoints serve them entirely:
 *
 * <pre>
 * QUERY /masterdata/products            GET / PATCH / DELETE /masterdata/products/{id}            POST
 * QUERY /masterdata/product-categories  GET / PATCH / DELETE /masterdata/product-categories/{id}  POST
 * </pre>
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
        .declareEntity("masterdata.product", "products")
        .declareEntity("masterdata.productCategory", "product categories");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(Product.class, ProductCategory.class);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityEndpoints crud = serviceRegistry.get(EntityEndpoints.class);
    crud.register(routes, "/masterdata/products", Product.DEF, "masterdata.product");
    crud.register(routes, "/masterdata/product-categories", ProductCategory.DEF, "masterdata.productCategory");
  }
}

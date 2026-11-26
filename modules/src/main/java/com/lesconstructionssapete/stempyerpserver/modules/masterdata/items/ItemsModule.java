package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.operations.FindBaseCurrencyOperation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordLayout;
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
    crud.rules(Product.DEF, new ProductService(new FindBaseCurrencyOperation(new ItemRepository())));
    crud.register(routes, "/masterdata/product-categories", ProductCategory.DEF, "masterdata.productCategory");

    // Record layouts (docs/ARCHITECTURE.md §23.7)
    crud.layout(Product.DEF, RecordLayout.of(Product.DEF)
        .section("general", 2, Product.PRODUCT_NO, Product.NAME, Product.PRODUCT_CATEGORY_ID, Product.IS_ENABLED)
        .section("pricing", 2, Product.UNIT_AMOUNT, Product.CURRENCY_ID, Product.UNIT_OF_MEASURE_ID)
        .tab(RecordLayout.HISTORY));
    crud.layout(ProductCategory.DEF, RecordLayout.of(ProductCategory.DEF)
        .section("general", 2, ProductCategory.CODE, ProductCategory.DESCRIPTION, ProductCategory.IS_ENABLED)
        .collection(ProductCategory.PRODUCTS_REL, c -> c
            .columns(Product.PRODUCT_NO, Product.NAME, Product.UNIT_AMOUNT, Product.IS_ENABLED))
        .tab(RecordLayout.HISTORY));
  }
}

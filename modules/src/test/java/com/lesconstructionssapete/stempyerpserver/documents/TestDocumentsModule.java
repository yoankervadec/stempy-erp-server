package com.lesconstructionssapete.stempyerpserver.documents;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.Document;
import com.lesconstructionssapete.stempyerpserver.platform.web.DocumentEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/** Test-only: the test order as a document at /test/orders (its tables come from the test's seed). */
public final class TestDocumentsModule implements ERPModule {

  public static final String PATH = "/test/orders";

  @Override
  public String name() {
    return "test.documents";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    permissions.declareEntity("test.order", "test orders");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(TestOrder.class, TestOrderLine.class, TestPayment.class);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry services) {
    services.get(DocumentEndpoints.class).register(routes, PATH, Document.of(TestOrder.DEF)
        .collection(TestOrder.LINES_REL)
        .collection(TestOrder.PAYMENTS_REL)
        .rules(new TestOrderService()), "test.order");
  }
}

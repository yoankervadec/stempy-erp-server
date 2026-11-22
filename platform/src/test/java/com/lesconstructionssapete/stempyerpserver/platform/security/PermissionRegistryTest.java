package com.lesconstructionssapete.stempyerpserver.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;

class PermissionRegistryTest {

  @Test
  void acceptsWellFormedNames() {
    var r = new PermissionRegistry()
        .declare("masterdata.product.read", "View products")
        .declare("masterdata.product.field.unitAmount.read", "See unit amounts");

    assertTrue(r.contains("masterdata.product.read"));
    assertEquals(2, r.all().size());
  }

  @Test
  void rejectsMalformedNames() {
    for (String bad : new String[] { "product.read", "Masterdata.product.read", "masterdata..read",
        "masterdata.product.read.", "masterdata.product-x.read", "masterdata.Product.read" }) {
      assertThrows(IllegalArgumentException.class, () -> new PermissionRegistry().declare(bad, "x"), bad);
    }
  }

  @Test
  void rejectsDuplicatesAndLateDeclarations() {
    var r = new PermissionRegistry().declare("a.b.c", "x");
    assertThrows(IllegalStateException.class, () -> r.declare("a.b.c", "again"));

    r.freeze();
    assertThrows(IllegalStateException.class, () -> r.declare("a.b.d", "late"));
  }

  @Test
  void entityFieldPermissionsMustBeDeclared() {
    // TestWidget.COST requires widget.cost.read
    var entities = new EntityRegistry().registerAll(TestWidget.class);
    var declared = new PermissionRegistry();
    assertThrows(IllegalStateException.class, () -> entities.freeze(declared::contains));

    declared.declare("widget.cost.read", "See widget costs");
    new EntityRegistry().registerAll(TestWidget.class).freeze(declared::contains);
  }

  @Test
  void checkerDeniesMissingPermissionsAndRejectsUndeclaredOnes() {
    var checker = new PermissionChecker(new PermissionRegistry().declare("a.b.read", "x").declare("a.b.write", "y"));
    var rc = new com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext(
        "req", 1, 1, java.util.Set.of("a.b.read"));

    checker.require(rc, "a.b.read");
    var e = assertThrows(com.lesconstructionssapete.stempyerpserver.platform.error.ForbiddenException.class,
        () -> checker.require(rc, "a.b.write"));
    assertEquals("PERMISSION_DENIED", e.code());
    assertThrows(IllegalStateException.class, () -> checker.require(rc, "a.b.typo"));
  }
}

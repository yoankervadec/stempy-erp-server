package com.lesconstructionssapete.stempyerpserver.modules.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;

/**
 * Multi-company access and permissions against the real schema: company
 * selection (X-Company-Id, default, only company), roles, all-permissions
 * roles, GRANT / DENY overrides, stale permission names, and role assignment
 * taking effect at once (cache invalidation). The permission cache uses its
 * production TTL here, so "at once" really tests invalidation.
 *
 * Uses the masterdata product / productCategory permissions.
 */
class AccessRoutesMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final String PRODUCT_READ = "masterdata.product.read";
  private static final String CATEGORY_READ = "masterdata.productCategory.read";

  private static MySqlTestStack stack;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(AccessRoutesMySqlTest.class, AuthSettings.defaults(), AccessRoutesMySqlTest::seed);
    for (String userNo : List.of("ADMIN", "MULTI", "STALE", "NOROLE", "MOVED", "NEWBIE")) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  /**
   * <pre>
   * user    default  member of (role)                       overrides
   * ADMIN   ACME     ACME (ADMINISTRATOR, all permissions)   DENY category read in ACME
   * MULTI   -        ACME (READER), BETA (CATALOG)
   * STALE   ACME     ACME (OLD: a permission no longer declared)  GRANT category read in ACME
   * NOROLE  ACME     ACME (no role; a DISABLED role)
   * MOVED   ACME     ACME (membership disabled), BETA
   * NEWBIE  -        DELTA (no role)
   * GAMMA is a disabled company.
   * </pre>
   */
  private static void seed(Statement st) throws SQLException {
    st.execute("""
        INSERT INTO companies (id, code, description, is_enabled) VALUES
          (1, 'ACME', 'Acme', TRUE), (2, 'BETA', 'Beta', TRUE), (3, 'GAMMA', 'Gamma', FALSE),
          (4, 'DELTA', 'Delta', TRUE)""");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'ADMIN', 'Admin', 1, 1),
          (2, NULL, 'MULTI', 'Multi', 1, 1),
          (3, 1, 'STALE', 'Stale', 1, 1),
          (4, 1, 'NOROLE', 'No role', 1, 1),
          (5, 1, 'MOVED', 'Moved', 1, 1),
          (6, NULL, 'NEWBIE', 'Newbie', 1, 1)""");
    st.execute("""
        INSERT INTO user_companies (user_id, company_id, is_enabled) VALUES
          (1, 1, TRUE), (1, 3, TRUE),
          (2, 1, TRUE), (2, 2, TRUE),
          (3, 1, TRUE),
          (4, 1, TRUE),
          (5, 1, FALSE), (5, 2, TRUE),
          (6, 4, TRUE)""");
    // ADMINISTRATOR (all permissions) is seeded by the V4 migration
    st.execute("""
        INSERT INTO roles (id, code, name, is_enabled) VALUES
          (10, 'READER', 'Reader', TRUE), (11, 'CATALOG', 'Catalog', TRUE), (12, 'OLD', 'Old', TRUE),
          (13, 'DISABLED', 'Disabled', FALSE)""");
    st.execute(String.format("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, '%1$s'), (11, '%1$s'), (11, '%2$s'), (12, 'masterdata.old.thing'), (13, '%1$s')""",
        PRODUCT_READ, CATEGORY_READ));
    st.execute("""
        INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES
          (1, 1, (SELECT id FROM roles WHERE code = 'ADMINISTRATOR')),
          (2, 1, 10), (2, 2, 11), (3, 1, 12), (4, 1, 13)""");
    st.execute(String.format("""
        INSERT INTO user_permission_overrides (user_id, company_id, permission, effect) VALUES
          (1, 1, '%1$s', 'DENY'), (3, 1, '%1$s', 'GRANT')""", CATEGORY_READ));
    // product 1 (ACME) was created by MULTI, whose default company is none
    st.execute("""
        INSERT INTO products (id, company_id, product_no, name, unit_of_measure_id, unit_amount, currency_id,
                              product_category_id, created_by, updated_by) VALUES
          (1, 1, 'P-A', 'Acme product', 1, 1, 1, 1, 2, 2),
          (2, 2, 'P-B', 'Beta product', 1, 1, 1, 1, 1, 1)""");
  }

  // ---- helpers ----

  private static HttpResponse<String> send(String method, String path, String token, Long company)
      throws Exception {
    return stack.send(method, path, method.equals("QUERY") ? "" : null, token, company);
  }

  private static JsonNode ok(HttpResponse<String> r) throws Exception {
    assertEquals(200, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static void assertRejected(int status, String code, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    assertEquals(code, MySqlTestStack.json(r).get("code").asText());
  }

  private static List<String> permissions(JsonNode me) {
    List<String> out = new ArrayList<>();
    me.get("permissions").forEach(p -> out.add(p.asText()));
    return out;
  }

  // ---- roles and overrides ----

  @Test
  void administratorGetsEveryPermissionExceptADeniedOne() throws Exception {
    String token = stack.login("ADMIN", PASSWORD);

    // every declared permission (including ones added by later releases) but the denied one
    List<String> granted = permissions(ok(send("GET", "/auth/me", token, null)));
    assertTrue(granted.contains(PRODUCT_READ) && granted.contains("masterdata.product.create"), granted.toString());
    assertTrue(!granted.contains(CATEGORY_READ), granted.toString());
    ok(send("QUERY", "/masterdata/products", token, null));
    assertRejected(403, "PERMISSION_DENIED", send("QUERY", "/masterdata/product-categories", token, null));
  }

  @Test
  void permissionsDependOnTheRequestsCompany() throws Exception {
    String token = stack.login("MULTI", PASSWORD);

    // ACME: READER
    assertEquals(List.of(PRODUCT_READ), permissions(ok(send("GET", "/auth/me", token, 1L))));
    assertRejected(403, "PERMISSION_DENIED", send("QUERY", "/masterdata/product-categories", token, 1L));
    // BETA: CATALOG, and only BETA's rows
    assertEquals(List.of(PRODUCT_READ, CATEGORY_READ), permissions(ok(send("GET", "/auth/me", token, 2L))));
    JsonNode beta = ok(send("QUERY", "/masterdata/products", token, 2L));
    assertEquals(1, beta.get("data").size());
    assertEquals("P-B", beta.get("data").get(0).get("productNo").asText());
  }

  @Test
  void grantOverrideAddsAndStaleNamesAreIgnored() throws Exception {
    String token = stack.login("STALE", PASSWORD);

    assertEquals(List.of(CATEGORY_READ), permissions(ok(send("GET", "/auth/me", token, null))));
  }

  @Test
  void noUsableRoleMeansNoPermission() throws Exception {
    String token = stack.login("NOROLE", PASSWORD); // its only role is disabled

    assertEquals(List.of(), permissions(ok(send("GET", "/auth/me", token, null))));
    assertRejected(403, "PERMISSION_DENIED", send("QUERY", "/masterdata/products", token, null));
  }

  // ---- company selection ----

  @Test
  void loginListsTheCompaniesAndTheDefault() throws Exception {
    JsonNode body = MySqlTestStack.json(stack.send("POST", "/auth/login",
        MySqlTestStack.loginBody("MULTI", PASSWORD), null));

    assertEquals(2, body.get("companies").size());
    assertEquals("ACME", body.get("companies").get(0).get("code").asText());
    assertEquals("BETA", body.get("companies").get(1).get("code").asText());
    assertTrue(body.get("defaultCompanyId").isNull());
  }

  @Test
  void severalCompaniesWithoutADefaultNeedTheHeaderButLogoutDoesnt() throws Exception {
    String token = stack.login("MULTI", PASSWORD);

    assertRejected(400, "COMPANY_REQUIRED", send("GET", "/auth/me", token, null));
    assertEquals(204, send("POST", "/auth/logout", token, null).statusCode());
  }

  @Test
  void companiesTheUserCantWorkInAre403() throws Exception {
    String token = stack.login("ADMIN", PASSWORD);

    assertRejected(403, "COMPANY_ACCESS_DENIED", send("GET", "/auth/me", token, 2L)); // not a member
    assertRejected(403, "COMPANY_ACCESS_DENIED", send("GET", "/auth/me", token, 3L)); // company disabled
    assertRejected(403, "COMPANY_ACCESS_DENIED", send("GET", "/auth/me", token, 99L)); // no such company
  }

  @Test
  void anUnusableDefaultFallsBackToTheOnlyCompany() throws Exception {
    String token = stack.login("MOVED", PASSWORD); // default ACME, but that membership is disabled

    assertEquals("BETA", ok(send("GET", "/auth/me", token, null)).get("company").get("code").asText());
    assertRejected(403, "COMPANY_ACCESS_DENIED", send("GET", "/auth/me", token, 1L));
  }

  @Test
  void createdByUserResolvesWhateverTheUsersDefaultCompany() throws Exception {
    String token = stack.login("ADMIN", PASSWORD);
    String body = "{\"where\":{\"field\":\"createdByUser.userNo\",\"op\":\"equals\",\"value\":\"MULTI\"}}";

    JsonNode page = ok(stack.send("QUERY", "/masterdata/products", body, token));
    assertEquals(1, page.get("data").size());
  }

  // ---- assign-role ----

  @Test
  void assignedRoleAppliesOnTheNextRequest() throws Exception {
    String token = stack.login("NEWBIE", PASSWORD);
    JsonNode before = ok(send("GET", "/auth/me", token, null)); // DELTA, its only company: now cached
    assertEquals("DELTA", before.get("company").get("code").asText());

    stack.users().assignRole("NEWBIE", "ACME", "READER");

    // the membership was added, ACME became the default, and the cache was cleared
    JsonNode after = ok(send("GET", "/auth/me", token, null));
    assertEquals("ACME", after.get("company").get("code").asText());
    assertEquals(List.of(PRODUCT_READ), permissions(after));
    ok(send("QUERY", "/masterdata/products", token, null));

    stack.users().assignRole("NEWBIE", "ACME", "READER"); // idempotent
  }

  @Test
  void assignRoleRejectsUnknownNames() {
    var e = assertThrows(ResourceNotFoundException.class, () -> stack.users().assignRole("NOBODY", "ACME", "READER"));
    assertEquals("USER_NOT_FOUND", e.code());
    e = assertThrows(ResourceNotFoundException.class, () -> stack.users().assignRole("ADMIN", "NOPE", "READER"));
    assertEquals("COMPANY_NOT_FOUND", e.code());
    e = assertThrows(ResourceNotFoundException.class, () -> stack.users().assignRole("ADMIN", "ACME", "NOPE"));
    assertEquals("ROLE_NOT_FOUND", e.code());
  }
}

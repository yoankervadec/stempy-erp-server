package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;

/**
 * End-to-end check of the items routes against a real MySQL: Flyway
 * migrations, HikariCP, the generated SQL, JDBC type mapping and JSON output,
 * with real logins. See MySqlTestStack for the database setup; skipped when
 * the test database is unavailable.
 *
 * One seeded data set is shared by all tests, so tests only read.
 *
 * Seed data (company 1 is the default caller, logged in as U1; company 2 as
 * U2):
 *
 * <pre>
 * id  company  productNo  name                  unitAmount  category
 *  1  1        P-001      Hex Bolt M8           0.2500      HW
 *  2  1        P-002      Wood screw 50% off    0.1000      HW
 *  3  1        P-003      Wire 14 AWG           1.7500      EL
 *  4  1        P-004      Old bolt              1.0000      HW   (soft-deleted)
 *  5  2        P-B01      Hex Bolt M8           0.3000      HW-B
 * </pre>
 */
class ItemRoutesMySqlTest {

  private static final String PASSWORD = "test-password";

  private static MySqlTestStack stack;
  /** Session token per company id. */
  private static Map<Long, String> tokens;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(ItemRoutesMySqlTest.class, AuthSettings.defaults(), ItemRoutesMySqlTest::seed);
    stack.users().setPassword("U1", PASSWORD.toCharArray());
    stack.users().setPassword("U2", PASSWORD.toCharArray());
    tokens = Map.of(1L, stack.login("U1", PASSWORD), 2L, stack.login("U2", PASSWORD));
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE), (2, 'OTHER', TRUE)");
    st.execute("INSERT INTO currencies (id, code, is_enabled) VALUES (1, 'CAD', TRUE)");
    st.execute("INSERT INTO units_of_measure (id, code, is_enabled) VALUES (1, 'UN', TRUE)");
    st.execute("""
        INSERT INTO users (id, company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1),
          (2, 2, 'U2', 'Other admin', 2, 2)""");
    st.execute("""
        INSERT INTO product_categories (id, company_id, code, description, created_by, updated_by) VALUES
          (1, 1, 'HW', 'Hardware', 1, 1),
          (2, 1, 'EL', 'Electrical', 1, 1),
          (3, 2, 'HW-B', 'Other hardware', 2, 2)""");
    st.execute("""
        INSERT INTO products (id, company_id, product_no, name, unit_of_measure_id, unit_amount,
                              currency_id, product_category_id, created_by, updated_by, deleted_at) VALUES
          (1, 1, 'P-001', 'Hex Bolt M8',        1, 0.2500, 1, 1, 1, 1, NULL),
          (2, 1, 'P-002', 'Wood screw 50% off', 1, 0.1000, 1, 1, 1, 1, NULL),
          (3, 1, 'P-003', 'Wire 14 AWG',        1, 1.7500, 1, 2, 1, 1, NULL),
          (4, 1, 'P-004', 'Old bolt',           1, 1.0000, 1, 1, 1, 1, CURRENT_TIMESTAMP),
          (5, 2, 'P-B01', 'Hex Bolt M8',        1, 0.3000, 1, 3, 2, 2, NULL)""");
  }

  // ---- helpers ----

  private static HttpResponse<String> query(String path, String body, long companyId)
      throws IOException, InterruptedException {
    return stack.send("QUERY", path, body, tokens.get(companyId));
  }

  private static JsonNode ok(HttpResponse<String> r) throws IOException {
    assertEquals(200, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  /** Product ids returned for company 1. */
  private static List<Long> productIds(String body) throws Exception {
    return ids(ok(query("/masterdata/products", body, 1)));
  }

  private static List<Long> ids(JsonNode page) {
    List<Long> out = new ArrayList<>();
    page.get("data").forEach(row -> out.add(row.get("id").asLong()));
    return out;
  }

  private static String where(String field, String op, String jsonValue) {
    return "{\"where\":{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + jsonValue + "}}";
  }

  // ---- tenancy and soft delete ----

  @Test
  void emptyQueryListsOnlyTheCallersLiveProductsInIdOrder() throws Exception {
    assertEquals(List.of(1L, 2L, 3L), productIds(""));
  }

  @Test
  void anotherCompanySeesOnlyItsOwnProducts() throws Exception {
    assertEquals(List.of(5L), ids(ok(query("/masterdata/products", "", 2))));
  }

  // ---- JDBC type mapping and JSON shape ----

  @Test
  void rowIsMappedWithMySqlTypes() throws Exception {
    JsonNode row = ok(query("/masterdata/products", where("productNo", "equals", "\"P-001\""), 1))
        .get("data").get(0);

    assertEquals("Hex Bolt M8", row.get("name").asText());
    assertTrue(row.get("unitAmount").isTextual(), "decimals must be JSON strings");
    assertEquals("0.2500", row.get("unitAmount").asText()); // DECIMAL(19,4) scale kept
    assertTrue(row.get("isEnabled").asBoolean());
    assertEquals(0, row.get("version").asLong());
    assertEquals(1, row.get("productCategoryId").asLong());
    Instant.parse(row.get("createdAt").asText()); // TIMESTAMP -> ISO-8601 instant
    assertTrue(row.get("deletedAt").isNull());
    assertFalse(row.has("companyId"), "internal fields are never returned");
  }

  // ---- operators: these are where SQL dialect bugs show up ----

  @Test
  void containsIsCaseInsensitive() throws Exception {
    assertEquals(List.of(1L), productIds(where("name", "contains", "\"bolt\"")));
  }

  @Test
  void likeWildcardsInTheValueAreLiteral() throws Exception {
    assertEquals(List.of(2L), productIds(where("name", "contains", "\"0%\"")));
    // unescaped, '_' would match any character, i.e. every product
    assertEquals(List.of(), productIds(where("name", "contains", "\"_\"")));
  }

  @Test
  void startsWith() throws Exception {
    assertEquals(List.of(3L), productIds(where("name", "startsWith", "\"wi\"")));
  }

  @Test
  void notEquals() throws Exception {
    assertEquals(List.of(2L, 3L), productIds(where("productNo", "notEquals", "\"P-001\"")));
  }

  @Test
  void inList() throws Exception {
    assertEquals(List.of(1L, 3L), productIds(where("productNo", "in", "[\"P-001\", \"P-003\", \"P-B01\"]")));
  }

  @Test
  void decimalComparisonAcceptsStringValues() throws Exception {
    assertEquals(List.of(1L, 3L), productIds(where("unitAmount", "greaterThan", "\"0.2\"")));
  }

  @Test
  void booleanFilter() throws Exception {
    assertEquals(List.of(1L, 2L, 3L), productIds(where("isEnabled", "equals", "true")));
  }

  @Test
  void andOrNotCombine() throws Exception {
    String body = """
        {"where": {"and": [
          {"or": [
            {"field": "name", "op": "contains", "value": "bolt"},
            {"field": "name", "op": "contains", "value": "wire"}
          ]},
          {"not": {"field": "productNo", "op": "equals", "value": "P-003"}}
        ]}}""";
    assertEquals(List.of(1L), productIds(body));
  }

  // ---- relations (LEFT JOINs, company-scoped) ----

  @Test
  void filterThroughCategoryRelation() throws Exception {
    assertEquals(List.of(1L, 2L), productIds(where("category.code", "equals", "\"HW\"")));
  }

  @Test
  void sortThroughCategoryRelationThenById() throws Exception {
    String body = "{\"sort\":[{\"field\":\"category.code\",\"dir\":\"asc\"}]}";
    assertEquals(List.of(3L, 1L, 2L), productIds(body)); // EL before HW; ties by id
  }

  @Test
  void filterThroughCreatedByUserRelation() throws Exception {
    assertEquals(List.of(1L, 2L, 3L), productIds(where("createdByUser.name", "equals", "\"Admin\"")));
  }

  // ---- sorting and paging ----

  @Test
  void sortDescending() throws Exception {
    assertEquals(List.of(3L, 1L, 2L), productIds("{\"sort\":[{\"field\":\"unitAmount\",\"dir\":\"desc\"}]}"));
  }

  @Test
  void paging() throws Exception {
    JsonNode page = ok(query("/masterdata/products", "{\"page\":{\"limit\":1,\"offset\":1}}", 1));
    assertEquals(List.of(2L), ids(page));
    assertEquals(1, page.get("page").get("limit").asInt());
    assertEquals(1, page.get("page").get("offset").asInt());
  }

  // ---- errors and the second route ----

  @Test
  void nonFilterableFieldIs422() throws Exception {
    HttpResponse<String> r = query("/masterdata/products", where("currencyId", "equals", "1"), 1);
    assertEquals(422, r.statusCode(), r.body());
    assertEquals("NOT_FILTERABLE", MySqlTestStack.json(r).get("errors").get(0).get("code").asText());
  }

  @Test
  void productCategoriesRoute() throws Exception {
    JsonNode page = ok(query("/masterdata/product-categories", "{\"sort\":[{\"field\":\"code\"}]}", 1));
    List<String> codes = new ArrayList<>();
    page.get("data").forEach(row -> codes.add(row.get("code").asText()));
    assertEquals(List.of("EL", "HW"), codes);
  }
}

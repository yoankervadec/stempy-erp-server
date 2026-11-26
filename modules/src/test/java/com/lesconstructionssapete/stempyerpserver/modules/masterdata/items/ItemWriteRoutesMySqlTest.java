package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged;

/**
 * The generic write routes (EntityEndpoints) on products and categories,
 * against the real schema: server-filled columns, validation, uniqueness and
 * same-company references, optimistic locking, soft delete, permissions and
 * the EntityChanged events.
 *
 * Tests that change rows create their own, so they don't depend on order.
 */
class ItemWriteRoutesMySqlTest {

  private static final String PASSWORD = "test-password";
  private static final String PRODUCTS = "/masterdata/products";

  /** Every EntityChanged published, recorded by a test-only module. */
  private static final List<EntityChanged> EVENTS = new CopyOnWriteArrayList<>();

  private static MySqlTestStack stack;
  private static String editor; // U1: every masterdata permission in ACME (company 1)
  private static String reader; // U2: product read only, ACME
  private static String other; // U3: every masterdata permission in OTHER (company 2)

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    ERPModule recorder = new ERPModule() {
      @Override
      public String name() {
        return "test.recorder";
      }

      @Override
      public void registerEntities(EntityRegistry entityRegistry) {
      }

      @Override
      public void registerEventHandlers(EventBus eventBus, ServiceRegistry services) {
        eventBus.subscribe(EntityChanged.class, (uow, e) -> EVENTS.add(e));
      }
    };
    stack = MySqlTestStack.start(ItemWriteRoutesMySqlTest.class, AuthSettings.defaults(),
        ItemWriteRoutesMySqlTest::seed, List.of(recorder));
    for (String u : List.of("U1", "U2", "U3")) {
      stack.users().setPassword(u, PASSWORD.toCharArray());
    }
    editor = stack.login("U1", PASSWORD);
    reader = stack.login("U2", PASSWORD);
    other = stack.login("U3", PASSWORD);
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
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Editor', 1, 1), (2, 1, 'U2', 'Reader', 1, 1), (3, 2, 'U3', 'Other editor', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'EDITOR', 'Editor'), (11, 'READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'masterdata.product.read'), (10, 'masterdata.product.create'),
          (10, 'masterdata.product.update'), (10, 'masterdata.product.delete'),
          (10, 'masterdata.productCategory.read'), (10, 'masterdata.productCategory.create'),
          (10, 'masterdata.productCategory.update'), (10, 'masterdata.productCategory.delete'),
          (11, 'masterdata.product.read')""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 10), (2, 1, 11), (3, 2, 10)");
    st.execute("""
        INSERT INTO product_categories (id, company_id, code, description, created_by, updated_by, deleted_at) VALUES
          (1, 1, 'HW', 'Hardware', 1, 1, NULL),
          (2, 2, 'HW-B', 'Other hardware', 3, 3, NULL),
          (3, 1, 'OLD', 'Deleted category', 1, 1, CURRENT_TIMESTAMP)""");
    st.execute("""
        INSERT INTO products (id, company_id, product_no, name, unit_of_measure_id, unit_amount, currency_id,
                              product_category_id, created_by, updated_by, deleted_at) VALUES
          (1, 1, 'P-001', 'Hex Bolt M8', 1, 0.2500, 1, 1, 1, 1, NULL),
          (2, 1, 'P-DEL', 'Deleted product', 1, 1.0000, 1, 1, 1, 1, CURRENT_TIMESTAMP)""");
  }

  // ---- helpers ----

  private static String json(Object body) throws Exception {
    return JsonMapperFactory.newObjectMapper().writeValueAsString(body);
  }

  /** A valid new ACME product; {@code overrides} replace or add fields (null removes one). */
  private static Map<String, Object> product(String productNo, Object... overrides) {
    Map<String, Object> body = new HashMap<>(Map.of("productNo", productNo, "name", "Washer",
        "unitOfMeasureId", 1, "unitAmount", "0.0500", "currencyId", 1, "productCategoryId", 1));
    for (int i = 0; i < overrides.length; i += 2) {
      if (overrides[i + 1] == null) {
        body.remove((String) overrides[i]);
      } else {
        body.put((String) overrides[i], overrides[i + 1]);
      }
    }
    return body;
  }

  private static HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
    return stack.send(method, path, body == null ? null : json(body), token);
  }

  private static JsonNode created(Map<String, Object> body) throws Exception {
    HttpResponse<String> r = send("POST", PRODUCTS, body, editor);
    assertEquals(201, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static Map<String, String> fieldErrors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    Map<String, String> out = new HashMap<>();
    MySqlTestStack.json(r).get("errors").forEach(e -> out.put(e.get("field").asText(), e.get("code").asText()));
    return out;
  }

  private static void assertRejected(int status, String code, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    assertEquals(code, MySqlTestStack.json(r).get("code").asText());
  }

  private static List<EntityChanged> eventsFor(long id) {
    return EVENTS.stream().filter(e -> e.entity().equals("product") && e.id() == id).toList();
  }

  // ---- create ----

  @Test
  void createFillsTheServerColumnsAndReturnsTheRow() throws Exception {
    HttpResponse<String> r = send("POST", PRODUCTS, product("P-NEW"), editor);

    assertEquals(201, r.statusCode(), r.body());
    JsonNode row = MySqlTestStack.json(r);
    long id = row.get("id").asLong();
    assertEquals(PRODUCTS + "/" + id, r.headers().firstValue("Location").orElseThrow());
    assertEquals("P-NEW", row.get("productNo").asText());
    assertEquals("0.0500", row.get("unitAmount").asText());
    assertEquals(0, row.get("version").asLong());
    assertEquals(1, row.get("createdBy").asLong());
    assertEquals(1, row.get("updatedBy").asLong());
    assertEquals(row.get("createdAt"), row.get("updatedAt"));
    Instant.parse(row.get("createdAt").asText());
    assertTrue(row.get("isEnabled").asBoolean()); // database default
    assertFalse(row.has("companyId"));

    assertEquals(row, MySqlTestStack.json(send("GET", PRODUCTS + "/" + id, null, editor)));
    EntityChanged event = eventsFor(id).getFirst();
    assertTrue(event instanceof EntityChanged.Created);
    assertEquals(1, event.companyId());
    assertEquals(1, event.userId());
  }

  @Test
  void createReportsEveryFieldProblemAtOnce() throws Exception {
    var errors = fieldErrors(send("POST", PRODUCTS,
        Map.of("productNo", "P-X", "unitAmount", "-1", "createdBy", 99, "companyId", 2), editor));

    assertEquals(Map.of(
        "name", "REQUIRED", "unitOfMeasureId", "REQUIRED", "currencyId", "REQUIRED",
        "productCategoryId", "REQUIRED",
        "unitAmount", "TOO_SMALL",
        "createdBy", "READ_ONLY",
        "companyId", "UNKNOWN_FIELD"), errors);
  }

  @Test
  void codesAreUniquePerCompanyIncludingDeletedRows() throws Exception {
    assertEquals(Map.of("productNo", "ALREADY_EXISTS"), fieldErrors(send("POST", PRODUCTS, product("P-001"), editor)));
    assertEquals(Map.of("productNo", "ALREADY_EXISTS"), fieldErrors(send("POST", PRODUCTS, product("P-DEL"), editor)));

    // another company may use the same number
    HttpResponse<String> r = send("POST", PRODUCTS, product("P-001", "productCategoryId", 2), other);
    assertEquals(201, r.statusCode(), r.body());
  }

  @Test
  void referencesMustBeLiveRowsOfTheSameCompany() throws Exception {
    assertEquals(Map.of("productCategoryId", "INVALID_REFERENCE"), // OTHER's category
        fieldErrors(send("POST", PRODUCTS, product("P-REF1", "productCategoryId", 2), editor)));
    assertEquals(Map.of("productCategoryId", "INVALID_REFERENCE"), // soft-deleted
        fieldErrors(send("POST", PRODUCTS, product("P-REF2", "productCategoryId", 3), editor)));
    // no entity for currencies yet: the foreign key catches it
    assertRejected(422, "INVALID_REFERENCE", send("POST", PRODUCTS, product("P-REF3", "currencyId", 999), editor));
  }

  // ---- update ----

  @Test
  void updateChangesTheFieldsBumpsTheVersionAndStamps() throws Exception {
    long id = created(product("P-UPD")).get("id").asLong();

    HttpResponse<String> r = send("PATCH", PRODUCTS + "/" + id, Map.of("version", 0, "name", "Big washer"), editor);

    assertEquals(200, r.statusCode(), r.body());
    JsonNode row = MySqlTestStack.json(r);
    assertEquals("Big washer", row.get("name").asText());
    assertEquals(1, row.get("version").asLong());
    EntityChanged.Updated event = (EntityChanged.Updated) eventsFor(id).getLast();
    assertEquals(Set.of("name"), event.fields());
  }

  @Test
  void eventsCarryOldAndNewValuesOfWhatReallyChanged() throws Exception {
    JsonNode row = created(product("P-VAL", "unitAmount", "1.2500"));
    long id = row.get("id").asLong();

    EntityChanged.Created c = (EntityChanged.Created) eventsFor(id).getFirst();
    assertEquals(new EntityChanged.FieldChange(null, "P-VAL"), c.changes().get("productNo"));
    assertEquals(0, new BigDecimal("1.25").compareTo((BigDecimal) c.changes().get("unitAmount").newValue()));
    assertFalse(c.changes().containsKey("id")); // only what the client set

    // name really changes; unitAmount is sent with another scale but the same value.
    HttpResponse<String> r = send("PATCH", PRODUCTS + "/" + id,
        Map.of("version", 0, "name", "Steel washer", "unitAmount", "1.25"), editor);
    assertEquals(200, r.statusCode(), r.body());
    EntityChanged.Updated u = (EntityChanged.Updated) eventsFor(id).getLast();
    assertEquals(Set.of("name", "unitAmount"), u.fields());
    assertEquals(Map.of("name", new EntityChanged.FieldChange("Washer", "Steel washer")), u.changes());

    // A PATCH that changes nothing still writes (version, updated_at) but carries no changes.
    assertEquals(200, send("PATCH", PRODUCTS + "/" + id, Map.of("version", 1, "name", "Steel washer"), editor)
        .statusCode());
    assertEquals(Map.of(), eventsFor(id).getLast().changes());
  }

  @Test
  void aStaleVersionIs409() throws Exception {
    long id = created(product("P-STALE")).get("id").asLong();
    assertEquals(200, send("PATCH", PRODUCTS + "/" + id, Map.of("version", 0, "name", "First"), editor).statusCode());

    assertRejected(409, "VERSION_CONFLICT",
        send("PATCH", PRODUCTS + "/" + id, Map.of("version", 0, "name", "Second"), editor));
    assertEquals(Map.of("version", "REQUIRED"),
        fieldErrors(send("PATCH", PRODUCTS + "/" + id, Map.of("name", "No version"), editor)));
  }

  @Test
  void updateChecksUniquenessAgainstOtherRowsOnly() throws Exception {
    long id = created(product("P-UNQ")).get("id").asLong();

    assertEquals(Map.of("productNo", "ALREADY_EXISTS"),
        fieldErrors(send("PATCH", PRODUCTS + "/" + id, Map.of("version", 0, "productNo", "P-001"), editor)));
    assertEquals(200, // its own value is fine
        send("PATCH", PRODUCTS + "/" + id, Map.of("version", 0, "productNo", "P-UNQ"), editor).statusCode());
  }

  @Test
  void anotherCompanysRowIsNotFound() throws Exception {
    assertRejected(404, "NOT_FOUND", send("PATCH", PRODUCTS + "/1", Map.of("version", 0, "name", "x"), other));
    assertRejected(404, "NOT_FOUND", send("GET", PRODUCTS + "/1", null, other));
    assertRejected(404, "NOT_FOUND", send("DELETE", PRODUCTS + "/1?version=0", null, other));
    assertRejected(404, "NOT_FOUND", send("GET", PRODUCTS + "/abc", null, editor));
  }

  // ---- delete ----

  @Test
  void deleteIsSoftAndVersionGuarded() throws Exception {
    long id = created(product("P-DELME")).get("id").asLong();
    String one = PRODUCTS + "/" + id;

    assertEquals(Map.of("version", "REQUIRED"), fieldErrors(send("DELETE", one, null, editor)));
    assertRejected(409, "VERSION_CONFLICT", send("DELETE", one + "?version=7", null, editor));
    assertEquals(204, send("DELETE", one + "?version=0", null, editor).statusCode());

    assertRejected(404, "NOT_FOUND", send("GET", one, null, editor));
    assertRejected(404, "NOT_FOUND", send("DELETE", one + "?version=1", null, editor));
    assertTrue(eventsFor(id).getLast() instanceof EntityChanged.Deleted);
    // the number stays taken: the row still exists, soft-deleted
    assertEquals(Map.of("productNo", "ALREADY_EXISTS"), fieldErrors(send("POST", PRODUCTS, product("P-DELME"), editor)));
  }

  // ---- permissions ----

  @Test
  void eachActionNeedsItsOwnPermission() throws Exception {
    assertEquals(200, send("GET", PRODUCTS + "/1", null, reader).statusCode());
    assertRejected(403, "PERMISSION_DENIED", send("POST", PRODUCTS, product("P-RO"), reader));
    assertRejected(403, "PERMISSION_DENIED",
        send("PATCH", PRODUCTS + "/1", Map.of("version", 0, "name", "x"), reader));
    assertRejected(403, "PERMISSION_DENIED", send("DELETE", PRODUCTS + "/1?version=0", null, reader));
  }

  // ---- the second entity, same routes ----

  @Test
  void categoriesGetTheSameRoutes() throws Exception {
    HttpResponse<String> r = send("POST", "/masterdata/product-categories",
        Map.of("code", "EL", "description", "Electrical"), editor);
    assertEquals(201, r.statusCode(), r.body());
    assertEquals(Map.of("code", "ALREADY_EXISTS"), fieldErrors(send("POST", "/masterdata/product-categories",
        Map.of("code", "HW", "description", "Duplicate"), editor)));
  }

  // ---- GET /meta (docs/ARCHITECTURE.md §23.6) ----

  private static JsonNode metaResource(String token, String key) throws Exception {
    HttpResponse<String> r = send("GET", "/meta", null, token);
    assertEquals(200, r.statusCode(), r.body());
    for (JsonNode resource : MySqlTestStack.json(r).get("resources")) {
      if (resource.get("key").asText().equals(key)) {
        return resource;
      }
    }
    return null;
  }

  private static JsonNode metaField(JsonNode resource, String name) {
    for (JsonNode f : resource.get("fields")) {
      if (f.get("name").asText().equals(name)) {
        return f;
      }
    }
    return null;
  }

  @Test
  void metaDescribesProductsForTheEditor() throws Exception {
    JsonNode products = metaResource(editor, "product");

    assertEquals("masterdata.items", products.get("module").asText());
    assertEquals(PRODUCTS, products.get("path").asText());
    assertEquals("{\"read\":true,\"create\":true,\"update\":true,\"delete\":true,\"history\":false}",
        products.get("can").toString());
    JsonNode category = metaField(products, "productCategoryId");
    assertEquals("reference", category.get("type").asText());
    assertEquals("productCategory", category.get("ref").asText());
    assertTrue(metaField(products, "productNo").get("unique").asBoolean());
    assertTrue(metaResource(editor, "productCategory") != null);
    // GL is installed, but the editor holds no GL permission
    assertEquals(null, metaResource(editor, "glAccount"));
  }

  @Test
  void metaShowsTheReaderAReadOnlyProductWithoutCategories() throws Exception {
    JsonNode products = metaResource(reader, "product");

    assertFalse(products.get("can").get("create").asBoolean());
    assertFalse(metaField(products, "name").get("writable").get("update").asBoolean());
    // the reader can't read categories: a plain id, not a picker
    assertEquals("integer", metaField(products, "productCategoryId").get("type").asText());
    assertEquals(null, metaResource(reader, "productCategory"));
  }
}

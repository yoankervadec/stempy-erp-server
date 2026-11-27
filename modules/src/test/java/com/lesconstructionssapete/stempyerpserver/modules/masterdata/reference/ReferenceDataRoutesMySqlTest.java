package com.lesconstructionssapete.stempyerpserver.modules.masterdata.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;

/**
 * Currencies and units of measure (docs/ARCHITECTURE.md §24): install-wide
 * master data on the generic routes. One list for every company, codes
 * validated, a currency's code fixed once created, no delete.
 *
 * Tests that change rows create their own, so they don't depend on order.
 */
class ReferenceDataRoutesMySqlTest {

  private static final String PASSWORD = "test-password";
  private static final String CURRENCIES = "/masterdata/currencies";
  private static final String UNITS = "/masterdata/units-of-measure";

  private static MySqlTestStack stack;
  private static String editor; // U1: every currency / unit permission in ACME (company 1)
  private static String reader; // U2: read only, ACME
  private static String other; // U3: every currency / unit permission in OTHER (company 2)

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(ReferenceDataRoutesMySqlTest.class, AuthSettings.defaults(),
        ReferenceDataRoutesMySqlTest::seed);
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
    // like rows from before V16: nobody recorded as their creator (created_by NULL)
    st.execute("INSERT INTO currencies (id, code, name, is_enabled) VALUES (1, 'CAD', 'CAD', TRUE)");
    st.execute("INSERT INTO units_of_measure (id, code, description, is_enabled) VALUES (1, 'UN', 'UN', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Editor', 1, 1), (2, 1, 'U2', 'Reader', 1, 1), (3, 2, 'U3', 'Other editor', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'EDITOR', 'Editor'), (11, 'READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'masterdata.currency.read'), (10, 'masterdata.currency.create'), (10, 'masterdata.currency.update'),
          (10, 'masterdata.unitOfMeasure.read'), (10, 'masterdata.unitOfMeasure.create'),
          (10, 'masterdata.unitOfMeasure.update'),
          (11, 'masterdata.currency.read'), (11, 'masterdata.unitOfMeasure.read')""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 10), (2, 1, 11), (3, 2, 10)");
  }

  // ---- helpers ----

  private static HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
    return stack.send(method, path, body == null ? null : JsonMapperFactory.newObjectMapper().writeValueAsString(body),
        token);
  }

  private static JsonNode created(String path, Map<String, Object> body, String token) throws Exception {
    HttpResponse<String> r = send("POST", path, body, token);
    assertEquals(201, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static Map<String, String> fieldErrors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    Map<String, String> out = new HashMap<>();
    MySqlTestStack.json(r).get("errors").forEach(e -> out.put(e.get("field").asText(), e.get("code").asText()));
    return out;
  }

  private static List<String> codes(String path, String token) throws Exception {
    HttpResponse<String> r = stack.send("QUERY", path, "{\"limit\":100}", token);
    assertEquals(200, r.statusCode(), r.body());
    List<String> out = new java.util.ArrayList<>();
    MySqlTestStack.json(r).get("data").forEach(row -> out.add(row.get("code").asText()));
    return out;
  }

  // ---- currencies ----

  @Test
  void aCurrencyIsCreatedOnceForEveryCompany() throws Exception {
    JsonNode row = created(CURRENCIES, Map.of("code", "USD", "name", "US dollar"), editor);

    assertEquals("USD", row.get("code").asText());
    assertEquals("US dollar", row.get("name").asText());
    assertTrue(row.get("isEnabled").asBoolean());
    assertEquals(1, row.get("createdBy").asLong());
    assertFalse(row.has("companyId"));
    assertTrue(codes(CURRENCIES, other).contains("USD")); // the other company sees it too
    assertEquals(Map.of("code", "ALREADY_EXISTS"), // and can't add it again
        fieldErrors(send("POST", CURRENCIES, Map.of("code", "USD", "name", "Again"), other)));
  }

  @Test
  void aCurrencyCodeMustBeAnIso4217CodeWithAMinorUnit() throws Exception {
    for (String bad : List.of("usd", "ZZZ", "XAU", "CA", "")) {
      Map<String, String> errors = fieldErrors(send("POST", CURRENCIES, Map.of("code", bad, "name", "Bad"), editor));
      assertTrue(Map.of("code", "INVALID_FORMAT").equals(errors) || Map.of("code", "REQUIRED").equals(errors),
          bad + ": " + errors);
    }
    assertEquals(Map.of("name", "REQUIRED"), fieldErrors(send("POST", CURRENCIES, Map.of("code", "EUR"), editor)));
  }

  @Test
  void aCurrencyKeepsItsCodeButItsNameAndStateChange() throws Exception {
    JsonNode row = created(CURRENCIES, Map.of("code", "GBP", "name", "Pound"), editor);
    String path = CURRENCIES + "/" + row.get("id").asLong();

    HttpResponse<String> r = send("PATCH", path, Map.of("version", 0, "name", "Pound sterling", "isEnabled", false),
        editor);
    assertEquals(200, r.statusCode(), r.body());
    assertEquals("Pound sterling", MySqlTestStack.json(r).get("name").asText());
    assertFalse(MySqlTestStack.json(r).get("isEnabled").asBoolean());

    r = send("PATCH", path, Map.of("version", 1, "code", "JPY"), editor);
    assertEquals(422, r.statusCode(), r.body());
    assertEquals("GBP", MySqlTestStack.json(send("GET", path, null, editor)).get("code").asText());
  }

  @Test
  void aCurrencyCreatedBeforeV16CanBeRenamed() throws Exception {
    HttpResponse<String> r = send("GET", CURRENCIES + "/1", null, reader);
    assertEquals(200, r.statusCode(), r.body());
    assertTrue(MySqlTestStack.json(r).get("createdBy").isNull());

    r = send("PATCH", CURRENCIES + "/1", Map.of("version", 0, "name", "Canadian dollar"), editor);
    assertEquals(200, r.statusCode(), r.body());
    assertEquals(1, MySqlTestStack.json(r).get("updatedBy").asLong());
  }

  // ---- units of measure ----

  @Test
  void aUnitIsSharedAndItsCodeCanBeCorrected() throws Exception {
    JsonNode row = created(UNITS, Map.of("code", "BXO", "description", "Box"), editor);
    assertTrue(codes(UNITS, other).contains("BXO"));

    HttpResponse<String> r = send("PATCH", UNITS + "/" + row.get("id").asLong(), Map.of("version", 0, "code", "BOX"),
        other);
    assertEquals(200, r.statusCode(), r.body());
    assertEquals("BOX", MySqlTestStack.json(r).get("code").asText());
  }

  @Test
  void aUnitCodeIsShortUpperCase() throws Exception {
    assertEquals(Map.of("code", "INVALID_FORMAT"),
        fieldErrors(send("POST", UNITS, Map.of("code", "kg", "description", "Kilogram"), editor)));
    assertEquals(Map.of("code", "INVALID_FORMAT"),
        fieldErrors(send("POST", UNITS, Map.of("code", "1KG", "description", "Kilogram"), editor)));
    assertEquals(Map.of("description", "REQUIRED"), fieldErrors(send("POST", UNITS, Map.of("code", "KG"), editor)));
    assertEquals(Map.of("code", "ALREADY_EXISTS"),
        fieldErrors(send("POST", UNITS, Map.of("code", "UN", "description", "Unit"), editor)));
  }

  // ---- both ----

  @Test
  void neitherCanBeDeleted() throws Exception {
    assertEquals(404, send("DELETE", CURRENCIES + "/1?version=0", null, editor).statusCode());
    assertEquals(404, send("DELETE", UNITS + "/1?version=0", null, editor).statusCode());
  }

  @Test
  void readersCanOnlyRead() throws Exception {
    assertTrue(codes(CURRENCIES, reader).contains("CAD"));
    assertTrue(codes(UNITS, reader).contains("UN"));
    assertEquals(403, send("POST", CURRENCIES, Map.of("code", "CHF", "name", "Franc"), reader).statusCode());
    assertEquals(403, send("POST", UNITS, Map.of("code", "M", "description", "Metre"), reader).statusCode());
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
 * Customers (docs/ARCHITECTURE.md §26) on the generic routes, against the
 * real schema: defaults, numbers per company, Canadian address rules on
 * create and update, field formats, permissions.
 *
 * Tests that change rows create their own customers, so they don't depend on
 * order.
 */
class CustomerRoutesMySqlTest {

  private static final String PASSWORD = "test-password";
  private static final String CUSTOMERS = "/masterdata/customers";
  private static final long CAD = 1, USD = 2;

  private static MySqlTestStack stack;
  private static String editor; // U1: every customer permission in ACME (company 1)
  private static String reader; // U2: read only, ACME
  private static String other; // U3: every customer permission in OTHER (company 2, base currency USD)

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(CustomerRoutesMySqlTest.class, AuthSettings.defaults(),
        CustomerRoutesMySqlTest::seed);
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
    st.execute("""
        INSERT INTO companies (id, code, is_enabled, base_currency_code) VALUES
          (1, 'ACME', TRUE, 'CAD'), (2, 'OTHER', TRUE, 'USD')""");
    st.execute("INSERT INTO currencies (id, code, name, is_enabled) VALUES (1, 'CAD', 'Canadian dollar', TRUE), "
        + "(2, 'USD', 'US dollar', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Editor', 1, 1), (2, 1, 'U2', 'Reader', 1, 1), (3, 2, 'U3', 'Other editor', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'EDITOR', 'Editor'), (11, 'READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'masterdata.customer.read'), (10, 'masterdata.customer.create'),
          (10, 'masterdata.customer.update'), (10, 'masterdata.customer.delete'),
          (11, 'masterdata.customer.read')""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 10), (2, 1, 11), (3, 2, 10)");
  }

  // ---- helpers ----

  private static HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
    return stack.send(method, path, body == null ? null : JsonMapperFactory.newObjectMapper().writeValueAsString(body),
        token);
  }

  /** A valid new customer, numbered {@code no} (null: by the series); {@code more} adds or replaces fields (null removes one). */
  private static Map<String, Object> customer(String no, Object... more) {
    Map<String, Object> body = new HashMap<>(Map.of("name", "Customer " + no, "currencyId", CAD,
        "countryCode", "CA"));
    if (no != null) {
      body.put("customerNo", no);
    }
    for (int i = 0; i < more.length; i += 2) {
      if (more[i + 1] == null) {
        body.remove((String) more[i]);
      } else {
        body.put((String) more[i], more[i + 1]);
      }
    }
    return body;
  }

  private static JsonNode created(Map<String, Object> body, String token) throws Exception {
    HttpResponse<String> r = send("POST", CUSTOMERS, body, token);
    assertEquals(201, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static Map<String, String> fieldErrors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    Map<String, String> out = new HashMap<>();
    MySqlTestStack.json(r).get("errors").forEach(e -> out.put(e.get("field").asText(), e.get("code").asText()));
    return out;
  }

  private static List<String> numbers(String body, String token) throws Exception {
    HttpResponse<String> r = stack.send("QUERY", CUSTOMERS, body, token);
    assertEquals(200, r.statusCode(), r.body());
    List<String> out = new ArrayList<>();
    MySqlTestStack.json(r).get("data").forEach(row -> out.add(row.get("customerNo").asText()));
    return out;
  }

  // ---- create ----

  @Test
  void aNewCustomerStartsInTheCompanysCurrencyAndInCanada() throws Exception {
    JsonNode form = MySqlTestStack.json(send("GET", CUSTOMERS + "/new", null, editor));
    assertEquals(CAD, form.get("currencyId").asLong());
    assertTrue(form.path("customerNo").isMissingNode() || form.get("customerNo").isNull()); // given on save
    assertEquals("CA", form.get("countryCode").asText());
    assertEquals(USD, MySqlTestStack.json(send("GET", CUSTOMERS + "/new", null, other)).get("currencyId").asLong());

    JsonNode row = created(customer("C-NEW"), editor);
    assertTrue(row.get("isEnabled").asBoolean());
    assertTrue(row.get("language").isNull());
    assertEquals(1, row.get("createdBy").asLong());
  }

  @Test
  void aNumberIsUniqueInTheCompanyAndNeverReused() throws Exception {
    JsonNode first = created(customer("C-DUP"), editor);
    assertEquals(Map.of("customerNo", "ALREADY_EXISTS"), fieldErrors(send("POST", CUSTOMERS, customer("C-DUP"), editor)));
    created(customer("C-DUP", "currencyId", USD), other); // another company

    assertEquals(204, send("DELETE", CUSTOMERS + "/" + first.get("id").asLong() + "?version=0", null, editor)
        .statusCode());
    assertEquals(Map.of("customerNo", "ALREADY_EXISTS"), fieldErrors(send("POST", CUSTOMERS, customer("C-DUP"), editor)));
    assertEquals(404, send("GET", CUSTOMERS + "/" + first.get("id").asLong(), null, editor).statusCode());
  }

  @Test
  void fieldsAreChecked() throws Exception {
    assertEquals(Map.of("name", "REQUIRED", "currencyId", "REQUIRED", "countryCode", "REQUIRED"),
        fieldErrors(send("POST", CUSTOMERS, Map.of(), editor)));
    assertEquals(Map.of("email", "INVALID_FORMAT", "language", "INVALID_FORMAT", "countryCode", "INVALID_FORMAT",
        "creditLimit", "TOO_SMALL", "phone", "INVALID_FORMAT"),
        fieldErrors(send("POST", CUSTOMERS, customer("C-BAD", "email", "nobody", "language", "de",
            "countryCode", "ZZ", "creditLimit", "-1", "phone", "call me"), editor)));
    assertEquals(Map.of("creditLimit", "TOO_MANY_DECIMALS"),
        fieldErrors(send("POST", CUSTOMERS, customer("C-BAD", "creditLimit", "10.001"), editor)));
    assertEquals(Map.of("currencyId", "INVALID_REFERENCE"),
        fieldErrors(send("POST", CUSTOMERS, customer("C-BAD", "currencyId", 999), editor)));
    created(customer("C-OK", "email", "ap@example.com", "phone", "+1 (514) 555-0100 ext. 12", "language", "en",
        "creditLimit", "5000.00"), editor);
  }

  // ---- numbers from the series ----

  @Test
  void aCustomerWithoutANumberGetsTheNextOfItsCompanysSeries() throws Exception {
    // its own company, so other tests' customers don't move the counter
    String first = created(customer(null, "currencyId", USD), other).get("customerNo").asText();
    assertTrue(first.matches("CA\\d{6}"), first);
    int n = Integer.parseInt(first.substring(2));
    assertEquals(String.format("CA%06d", n + 1),
        created(customer(null, "currencyId", USD), other).get("customerNo").asText());

    // a number typed in ahead of the series (an import) is skipped, not refused
    created(customer(String.format("CA%06d", n + 2), "currencyId", USD), other);
    assertEquals(String.format("CA%06d", n + 3),
        created(customer(null, "currencyId", USD, "name", "Next"), other).get("customerNo").asText());
  }

  @Test
  void aNumberIsFixedOnceCreatedAndNeverBlank() throws Exception {
    JsonNode row = created(customer(null), editor);
    HttpResponse<String> r = send("PATCH", CUSTOMERS + "/" + row.get("id").asLong(),
        Map.of("version", 0, "customerNo", "X-1"), editor);
    assertEquals(422, r.statusCode(), r.body());
    assertEquals(Map.of("customerNo", "INVALID_FORMAT"), fieldErrors(send("POST", CUSTOMERS, customer(" "), editor)));
  }

  @Test
  void anInvalidBodyTakesNoNumber() throws Exception {
    String before = created(customer(null), editor).get("customerNo").asText();
    fieldErrors(send("POST", CUSTOMERS, customer(null, "countryCode", "ZZ"), editor));
    String after = created(customer(null), editor).get("customerNo").asText();
    assertEquals(Integer.parseInt(before.substring(2)) + 1, Integer.parseInt(after.substring(2)));
  }

  // ---- Canadian addresses ----

  @Test
  void aCanadianAddressHasARealProvinceAndPostalCode() throws Exception {
    assertEquals(Map.of("provinceCode", "UNKNOWN_PROVINCE", "postalCode", "INVALID_FORMAT"),
        fieldErrors(send("POST", CUSTOMERS, customer("C-CA1", "provinceCode", "XX", "postalCode", "h2x1y4"), editor)));
    assertEquals(Map.of("provinceCode", "UNKNOWN_PROVINCE"), // a US state in Canada
        fieldErrors(send("POST", CUSTOMERS, customer("C-CA2", "provinceCode", "NY"), editor)));
    created(customer("C-CA3", "provinceCode", "QC", "postalCode", "H2X 1Y4", "city", "Montreal"), editor);
    created(customer("C-US1", "countryCode", "US", "provinceCode", "NY", "postalCode", "10001"), editor);
  }

  @Test
  void anUpdateIsCheckedAsTheWholeAddress() throws Exception {
    JsonNode row = created(customer("C-MOVE", "provinceCode", "ON", "postalCode", "K1A 0B1"), editor);
    String path = CUSTOMERS + "/" + row.get("id").asLong();

    // the stored country (CA) with the new province
    assertEquals(Map.of("provinceCode", "UNKNOWN_PROVINCE"),
        fieldErrors(send("PATCH", path, Map.of("version", 0, "provinceCode", "NY"), editor)));
    HttpResponse<String> moved = send("PATCH", path, Map.of("version", 0, "countryCode", "US", "provinceCode", "NY",
        "postalCode", "10001"), editor);
    assertEquals(200, moved.statusCode(), moved.body());
    // back to Canada with the US postal code still there
    assertEquals(Map.of("postalCode", "INVALID_FORMAT"),
        fieldErrors(send("PATCH", path, Map.of("version", 1, "countryCode", "CA", "provinceCode", "QC"), editor)));
  }

  // ---- reads and permissions ----

  @Test
  void customersAreTheCompanysOwnAndFilterableByCurrencyCode() throws Exception {
    created(customer("C-USD", "currencyId", USD), editor);
    List<String> usd = numbers("""
        {"where": {"field": "currency.code", "op": "equals", "value": "USD"}}""", reader);
    assertTrue(usd.contains("C-USD"), usd.toString());
    assertTrue(numbers("{}", other).stream().noneMatch("C-USD"::equals));
  }

  @Test
  void readersCanOnlyRead() throws Exception {
    assertEquals(403, send("POST", CUSTOMERS, customer("C-RO"), reader).statusCode());
  }
}

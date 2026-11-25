package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.NumberRequest;

/**
 * The numbering admin API over HTTP: permissions and company isolation,
 * validation against document types, defaults, versions, frozen patterns,
 * counters, set-next and history.
 *
 * Users: U1 administrator of ACME (every permission), U2 numbering manager of
 * ACME (read + manage only), U3 numbering reader of ACME, U4 numbering manager
 * of OTHER, U5 member of ACME with no permission.
 */
class NumberingRoutesMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final long ACME = 1;
  private static final long OTHER = 2;

  private static final String ORDER = "test.order";
  private static final String INVOICE = "test.invoice";
  private static final String ORDER_PATTERN = "CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}";

  /** Test document types, as a module would declare them. */
  static final class TestTypesModule implements ERPModule {
    @Override
    public String name() {
      return "test-numbering-types";
    }

    @Override
    public void registerEntities(EntityRegistry entityRegistry) {
    }

    @Override
    public void registerDocumentTypes(DocumentTypes types) {
      types.register(DocumentType.of(ORDER, "Test customer orders")
          .withParameters("STORE_NO", "TERMINAL_NO")
          .withDefaultSeries("CO", ORDER_PATTERN, Mode.GAPS_ALLOWED));
      types.register(DocumentType.of(INVOICE, "Test invoices")
          .gaplessRequired()
          .withDefaultSeries("INV", "INV-{SEQ:6}", Mode.GAPLESS));
    }
  }

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static DocumentNumbers numbers;
  private static String admin;
  private static String manager;
  private static String reader;
  private static String outsider;
  private static String nobody;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(NumberingRoutesMySqlTest.class, AuthSettings.defaults(),
        NumberingRoutesMySqlTest::seed, List.of(new TestTypesModule()));
    for (String userNo : new String[] { "U1", "U2", "U3", "U4", "U5" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
    admin = stack.login("U1", PASSWORD);
    manager = stack.login("U2", PASSWORD);
    reader = stack.login("U3", PASSWORD);
    outsider = stack.login("U4", PASSWORD);
    nobody = stack.login("U5", PASSWORD);
    uow = new UnitOfWorkFactory(stack.dataSource());
    numbers = stack.services().get(DocumentNumbers.class);
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  @BeforeEach
  @SuppressWarnings("unused")
  void noSeries() {
    uow.run(u -> {
      Jdbc.update(u, "DELETE FROM number_series_counters");
      Jdbc.update(u, "DELETE FROM number_series");
    });
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE), (2, 'OTHER', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1),
          (2, 1, 'U2', 'Numbering manager', 1, 1),
          (3, 1, 'U3', 'Numbering reader', 1, 1),
          (4, 2, 'U4', 'Other manager', 1, 1),
          (5, 1, 'U5', 'Nobody', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 1), (4, 2), (5, 1)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'NUM_MANAGER', 'Manager'), (11, 'NUM_READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'numbering.series.read'), (10, 'numbering.series.manage'), (11, 'numbering.series.read')""");
    st.execute("""
        INSERT INTO user_company_roles (user_id, company_id, role_id)
        SELECT 1, 1, id FROM roles WHERE code = 'ADMINISTRATOR'""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (2, 1, 10), (3, 1, 11), (4, 2, 10)");
  }

  // ---- helpers ----

  private static HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
    return stack.send(method, path, body, token, token.equals(outsider) ? OTHER : ACME);
  }

  private static JsonNode ok(int status, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static void rejected(int status, String code, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    assertEquals(code, MySqlTestStack.json(r).get("code").asText(), r.body());
  }

  /** field:code of every error of a 422. */
  private static List<String> errors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    List<String> out = new ArrayList<>();
    MySqlTestStack.json(r).get("errors").forEach(e -> out.add(e.get("field").asText() + ":" + e.get("code").asText()));
    return out.stream().sorted().toList();
  }

  private static JsonNode create(String body) throws Exception {
    return ok(201, call("POST", "/numbering/series", body, manager));
  }

  private static JsonNode createOrderSeries(String code, String pattern, String extra) throws Exception {
    return create("{\"documentType\":\"" + ORDER + "\",\"code\":\"" + code + "\",\"pattern\":\"" + pattern
        + "\",\"mode\":\"GAPS_ALLOWED\"" + extra + "}");
  }

  private static JsonNode get(long id) throws Exception {
    return ok(200, call("GET", "/numbering/series/" + id, null, reader));
  }

  private static String issue(NumberRequest request) {
    return uow.execute(u -> numbers.next(u, request));
  }

  private static NumberRequest order(String store, String terminal) {
    return NumberRequest.of(ACME, ORDER).param("STORE_NO", store).param("TERMINAL_NO", terminal);
  }

  // ---- document types ----

  @Test
  void documentTypes() throws Exception {
    JsonNode types = ok(200, call("GET", "/numbering/document-types", null, reader));
    JsonNode order = null;
    for (JsonNode t : types) {
      if (t.get("key").asText().equals(ORDER)) {
        order = t;
      }
    }
    assertTrue(order != null, types.toString());
    assertEquals("[\"STORE_NO\",\"TERMINAL_NO\"]", order.get("parameters").toString());
    assertFalse(order.get("gaplessRequired").asBoolean());
    assertEquals(ORDER_PATTERN, order.get("defaultSeries").get("pattern").asText());
    rejected(403, "PERMISSION_DENIED", call("GET", "/numbering/document-types", null, nobody));
  }

  // ---- create ----

  @Test
  void createTheFirstSeriesOfATypeMakesItTheDefault() throws Exception {
    HttpResponse<String> r = call("POST", "/numbering/series", """
        {"documentType":"test.order","code":"POS","description":"Stores","pattern":"P{STORE_NO:2}-{SEQ:5}",
         "mode":"gaps_allowed","startValue":100}""", manager);
    JsonNode s = ok(201, r);
    assertEquals("/numbering/series/" + s.get("id").asLong(), r.headers().firstValue("Location").orElseThrow());
    assertEquals("POS", s.get("code").asText());
    assertEquals("GAPS_ALLOWED", s.get("mode").asText());
    assertEquals(100, s.get("startValue").asLong());
    assertTrue(s.get("isDefault").asBoolean());
    assertTrue(s.get("isEnabled").asBoolean());
    assertFalse(s.get("used").asBoolean());
    assertTrue(s.get("typeRegistered").asBoolean());
    assertEquals(2, s.get("createdBy").asLong());
    // Numbers now come from it, not from the type's built-in CO.
    assertEquals("P07-00100", issue(order("7", "1")));
  }

  @Test
  void aSecondSeriesIsNotTheDefaultUnlessAsked() throws Exception {
    long first = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    JsonNode web = createOrderSeries("WEB", "WEB-{SEQ:5}", "");
    assertFalse(web.get("isDefault").asBoolean());
    assertTrue(get(first).get("isDefault").asBoolean());
  }

  @Test
  void aNewDefaultTakesTheFlagOffTheOldOne() throws Exception {
    JsonNode first = createOrderSeries("CO", ORDER_PATTERN, "");
    JsonNode second = createOrderSeries("WEB", "WEB-{SEQ:5}", ",\"isDefault\":true");
    assertTrue(second.get("isDefault").asBoolean());
    JsonNode old = get(first.get("id").asLong());
    assertFalse(old.get("isDefault").asBoolean());
    assertEquals(first.get("version").asInt() + 1, old.get("version").asInt());
    assertEquals("WEB-00001", issue(order("1", "1")));
  }

  @Test
  void createValidation() throws Exception {
    assertEquals(List.of("code:REQUIRED", "documentType:REQUIRED", "mode:REQUIRED", "pattern:REQUIRED"),
        errors(call("POST", "/numbering/series", "{}", manager)));
    assertEquals(List.of("code:INVALID_FORMAT", "id:READ_ONLY", "mode:INVALID_VALUE", "nope:UNKNOWN_FIELD",
        "startValue:OUT_OF_RANGE", "version:READ_ONLY"),
        errors(call("POST", "/numbering/series", """
            {"documentType":"test.order","code":"co x","pattern":"X{SEQ:4}","mode":"SOMETIMES","startValue":0,
             "id":1,"version":0,"nope":true}""", manager)));
    assertEquals(List.of("documentType:UNKNOWN_DOCUMENT_TYPE"), errors(call("POST", "/numbering/series", """
        {"documentType":"test.nope","code":"X","pattern":"X{SEQ:4}","mode":"GAPLESS"}""", manager)));
    assertEquals(List.of("pattern:INVALID_PATTERN"), errors(call("POST", "/numbering/series", """
        {"documentType":"test.order","code":"X","pattern":"X{CUSTOMER_NO:3}{SEQ:4}","mode":"GAPLESS"}""", manager)));
    assertEquals(List.of("mode:GAPLESS_REQUIRED"), errors(call("POST", "/numbering/series", """
        {"documentType":"test.invoice","code":"X","pattern":"X{SEQ:4}","mode":"GAPS_ALLOWED"}""", manager)));
  }

  @Test
  void codeIsUniquePerType() throws Exception {
    createOrderSeries("CO", ORDER_PATTERN, "");
    assertEquals(List.of("code:ALREADY_EXISTS"), errors(call("POST", "/numbering/series", """
        {"documentType":"test.order","code":"CO","pattern":"X{SEQ:4}","mode":"GAPLESS"}""", manager)));
    // Same code, another type: fine.
    create("""
        {"documentType":"test.invoice","code":"CO","pattern":"X{SEQ:4}","mode":"GAPLESS"}""");
  }

  @Test
  void createNeedsManage() throws Exception {
    rejected(403, "PERMISSION_DENIED", call("POST", "/numbering/series", """
        {"documentType":"test.order","code":"X","pattern":"X{SEQ:4}","mode":"GAPLESS"}""", reader));
  }

  // ---- read ----

  @Test
  void listIsPerCompanyAndFilterable() throws Exception {
    createOrderSeries("CO", ORDER_PATTERN, "");
    create("{\"documentType\":\"test.invoice\",\"code\":\"INV\",\"pattern\":\"I{SEQ:4}\",\"mode\":\"GAPLESS\"}");
    ok(201, call("POST", "/numbering/series",
        "{\"documentType\":\"test.order\",\"code\":\"THEIRS\",\"pattern\":\"T{SEQ:4}\",\"mode\":\"GAPLESS\"}",
        outsider));

    JsonNode all = ok(200, call("GET", "/numbering/series", null, reader));
    assertEquals(2, all.get("data").size());
    assertEquals(50, all.get("page").get("limit").asInt());
    JsonNode orders = ok(200, call("GET", "/numbering/series?documentType=test.order", null, reader));
    assertEquals(1, orders.get("data").size());
    assertEquals("CO", orders.get("data").get(0).get("code").asText());
    rejected(403, "PERMISSION_DENIED", call("GET", "/numbering/series", null, nobody));
    rejected(400, "INVALID_PARAMETER", call("GET", "/numbering/series?limit=0", null, reader));
  }

  @Test
  void anotherCompanysSeriesIsNotFound() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    rejected(404, "NOT_FOUND", call("GET", "/numbering/series/" + id, null, outsider));
    rejected(404, "NOT_FOUND", call("PATCH", "/numbering/series/" + id, "{\"version\":0}", outsider));
    rejected(404, "NOT_FOUND", call("GET", "/numbering/series/" + id + "/counters", null, outsider));
    rejected(404, "NOT_FOUND", call("GET", "/numbering/series/abc", null, reader));
    rejected(404, "NOT_FOUND", call("GET", "/numbering/series/999999", null, reader));
  }

  @Test
  void theBuiltInDefaultShowsUpOnceUsed() throws Exception {
    assertEquals("CO-00101000001", issue(order("1", "1")));
    JsonNode s = ok(200, call("GET", "/numbering/series", null, reader)).get("data").get(0);
    assertEquals("CO", s.get("code").asText());
    assertTrue(s.get("isDefault").asBoolean());
    assertTrue(s.get("used").asBoolean());
    assertTrue(s.get("createdBy").isNull());
  }

  // ---- change ----

  @Test
  void patchUnusedSeries() throws Exception {
    JsonNode s = createOrderSeries("CO", ORDER_PATTERN, "");
    long id = s.get("id").asLong();
    JsonNode changed = ok(200, call("PATCH", "/numbering/series/" + id, """
        {"version":0,"description":"Stores","pattern":"C{STORE_NO:3}{TERMINAL_NO:3}-{SEQ:4}","startValue":50}""",
        manager));
    assertEquals(1, changed.get("version").asInt());
    assertEquals("Stores", changed.get("description").asText());
    assertEquals(50, changed.get("startValue").asLong());
    assertEquals("C001001-0050", issue(order("1", "1")));
  }

  @Test
  void patchNeedsTheCurrentVersion() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    assertEquals(List.of("version:REQUIRED"),
        errors(call("PATCH", "/numbering/series/" + id, "{\"description\":\"x\"}", manager)));
    ok(200, call("PATCH", "/numbering/series/" + id, "{\"version\":0,\"description\":\"x\"}", manager));
    rejected(409, "VERSION_CONFLICT",
        call("PATCH", "/numbering/series/" + id, "{\"version\":0,\"description\":\"y\"}", manager));
  }

  @Test
  void aUsedSeriesKeepsItsShape() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    issue(order("1", "1"));
    assertTrue(get(id).get("used").asBoolean());
    for (String change : List.of("\"pattern\":\"X{SEQ:4}\"", "\"mode\":\"GAPLESS\"", "\"startValue\":7")) {
      rejected(409, "NUMBER_SERIES_IN_USE",
          call("PATCH", "/numbering/series/" + id, "{\"version\":0," + change + "}", manager));
    }
    // What doesn't change the numbers still can.
    JsonNode s = ok(200, call("PATCH", "/numbering/series/" + id,
        "{\"version\":0,\"description\":\"Stores\",\"pattern\":\"" + ORDER_PATTERN + "\",\"isEnabled\":false}",
        manager));
    assertFalse(s.get("isEnabled").asBoolean());
  }

  @Test
  void patchRules() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    assertEquals(List.of("code:READ_ONLY", "documentType:READ_ONLY", "used:READ_ONLY"),
        errors(call("PATCH", "/numbering/series/" + id, """
            {"version":0,"code":"X","documentType":"test.invoice","used":true}""", manager)));
    // Checked once the body is well formed.
    assertEquals(List.of("isDefault:CANNOT_UNSET"),
        errors(call("PATCH", "/numbering/series/" + id, "{\"version\":0,\"isDefault\":false}", manager)));
    assertEquals(List.of("pattern:INVALID_PATTERN"), errors(call("PATCH", "/numbering/series/" + id,
        "{\"version\":0,\"pattern\":\"CO{SEQ:4}{SEQ:4}\"}", manager)));
    rejected(403, "PERMISSION_DENIED", call("PATCH", "/numbering/series/" + id, "{\"version\":0}", reader));
  }

  @Test
  void patchMakesAnotherSeriesTheDefault() throws Exception {
    long co = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    long web = createOrderSeries("WEB", "WEB-{SEQ:5}", "").get("id").asLong();
    ok(200, call("PATCH", "/numbering/series/" + web, "{\"version\":0,\"isDefault\":true}", manager));
    assertFalse(get(co).get("isDefault").asBoolean());
    assertTrue(get(web).get("isDefault").asBoolean());
  }

  @Test
  void aGaplessRequiredTypeRefusesGapsOnPatch() throws Exception {
    long id = create("{\"documentType\":\"test.invoice\",\"code\":\"INV\",\"pattern\":\"I{SEQ:4}\","
        + "\"mode\":\"GAPLESS\"}").get("id").asLong();
    assertEquals(List.of("mode:GAPLESS_REQUIRED"), errors(call("PATCH", "/numbering/series/" + id,
        "{\"version\":0,\"mode\":\"GAPS_ALLOWED\"}", manager)));
  }

  // ---- counters ----

  @Test
  void countersShowTheNextNumber() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    issue(order("12", "3"));
    issue(order("12", "3"));
    issue(order("12", "4"));
    JsonNode counters = ok(200, call("GET", "/numbering/series/" + id + "/counters", null, reader));
    assertEquals(2, counters.size());
    JsonNode first = counters.get(0);
    assertEquals("STORE_NO=012;TERMINAL_NO=03", first.get("scope").asText());
    assertEquals("{\"STORE_NO\":\"012\",\"TERMINAL_NO\":\"03\"}", first.get("params").toString());
    assertEquals(2, first.get("lastNumber").asLong());
    assertEquals(3, first.get("nextNumber").asLong());
    assertEquals("CO-01203000003", first.get("next").asText());
  }

  @Test
  void setNextContinuesAnOldSystem() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    JsonNode counter = ok(200, call("POST", "/numbering/series/" + id + "/counters/set-next",
        "{\"params\":{\"STORE_NO\":\"12\",\"TERMINAL_NO\":3},\"nextNumber\":5001}", manager));
    assertEquals("CO-01203005001", counter.get("next").asText());
    assertEquals(5000, counter.get("lastNumber").asLong());
    assertEquals("CO-01203005001", issue(order("12", "3")));
    // Other terminals are untouched.
    assertEquals("CO-01204000001", issue(order("12", "4")));
  }

  @Test
  void setNextOnlyMovesUp() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    issue(order("1", "1"));
    issue(order("1", "1"));
    String path = "/numbering/series/" + id + "/counters/set-next";
    rejected(409, "COUNTER_CANNOT_DECREASE",
        call("POST", path, "{\"params\":{\"STORE_NO\":\"1\",\"TERMINAL_NO\":\"1\"},\"nextNumber\":2}", manager));
    // The current next number: accepted, nothing changes.
    JsonNode same = ok(200,
        call("POST", path, "{\"params\":{\"STORE_NO\":\"1\",\"TERMINAL_NO\":\"1\"},\"nextNumber\":3}", manager));
    assertEquals(2, same.get("lastNumber").asLong());
    ok(200, call("POST", path, "{\"params\":{\"STORE_NO\":\"1\",\"TERMINAL_NO\":\"1\"},\"nextNumber\":10}", manager));
    assertEquals("CO-00101000010", issue(order("1", "1")));
  }

  @Test
  void setNextValidation() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    String path = "/numbering/series/" + id + "/counters/set-next";
    assertEquals(List.of("nextNumber:REQUIRED"), errors(call("POST", path, "{}", manager)));
    assertEquals(List.of("nextNumber:OUT_OF_RANGE", "params.STORE_NO:INVALID_TYPE", "x:UNKNOWN_FIELD"),
        errors(call("POST", path, "{\"params\":{\"STORE_NO\":[1]},\"nextNumber\":0,\"x\":1}", manager)));
    assertEquals(List.of("params.STORE_NO:TOO_LONG", "params.TERMINAL_NO:REQUIRED"),
        errors(call("POST", path, "{\"params\":{\"STORE_NO\":\"1234\"},\"nextNumber\":5}", manager)));
    rejected(403, "PERMISSION_DENIED", call("POST", path,
        "{\"params\":{\"STORE_NO\":\"1\",\"TERMINAL_NO\":\"1\"},\"nextNumber\":5}", reader));
  }

  // ---- history ----

  @Test
  void historyRecordsChangesAndCounterMoves() throws Exception {
    long id = createOrderSeries("CO", ORDER_PATTERN, "").get("id").asLong();
    ok(200, call("PATCH", "/numbering/series/" + id, "{\"version\":0,\"description\":\"Stores\"}", manager));
    ok(200, call("POST", "/numbering/series/" + id + "/counters/set-next",
        "{\"params\":{\"STORE_NO\":\"1\",\"TERMINAL_NO\":\"1\"},\"nextNumber\":100}", manager));
    ok(200, call("POST", "/numbering/series/" + id + "/counters/set-next",
        "{\"params\":{\"STORE_NO\":\"1\",\"TERMINAL_NO\":\"1\"},\"nextNumber\":200}", manager));

    JsonNode history = ok(200, call("GET", "/numbering/series/" + id + "/history", null, admin)).get("data");
    List<String> actions = new ArrayList<>();
    history.forEach(e -> actions.add(e.get("action").asText()));
    assertEquals(List.of("COUNTER_SET", "COUNTER_SET", "UPDATED", "CREATED"), actions);
    JsonNode moved = history.get(0).get("changes").get("counters[STORE_NO=001;TERMINAL_NO=01].nextNumber");
    assertEquals(100, moved.get("old").asLong());
    assertEquals(200, moved.get("new").asLong());
    assertTrue(history.get(2).get("changes").has("description"));
    assertEquals(ORDER, history.get(3).get("changes").get("documentType").get("new").asText());
    // The manager role lacks audit.history.read.
    rejected(403, "PERMISSION_DENIED", call("GET", "/numbering/series/" + id + "/history", null, manager));
  }
}

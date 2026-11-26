package com.lesconstructionssapete.stempyerpserver.modules.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;

/**
 * The general ledger's first routes over HTTP (docs/ARCHITECTURE.md §22.10
 * step 1): the chart of accounts and fiscal years / periods. Permissions and
 * company isolation, validation, parents and cycles, versions, contiguous
 * years, close / reopen, history.
 *
 * Users: U1 administrator of ACME (every permission, including
 * gl.period.reopen), U2 accountant of ACME (accounts and periods: read +
 * manage, no reopen), U3 reader of ACME (read only), U4 accountant of OTHER.
 */
class GlRoutesMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final long ACME = 1;
  private static final long OTHER = 2;

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static String admin;
  private static String accountant;
  private static String reader;
  private static String outsider;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(GlRoutesMySqlTest.class, AuthSettings.defaults(), GlRoutesMySqlTest::seed);
    for (String userNo : new String[] { "U1", "U2", "U3", "U4" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
    admin = stack.login("U1", PASSWORD);
    accountant = stack.login("U2", PASSWORD);
    reader = stack.login("U3", PASSWORD);
    outsider = stack.login("U4", PASSWORD);
    uow = new UnitOfWorkFactory(stack.dataSource());
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
  void emptyLedger() {
    uow.run(u -> {
      Jdbc.update(u, "DELETE FROM gl_periods");
      Jdbc.update(u, "DELETE FROM gl_fiscal_years");
      Jdbc.update(u, "UPDATE gl_accounts SET parent_id = NULL");
      Jdbc.update(u, "DELETE FROM gl_accounts");
    });
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE), (2, 'OTHER', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1),
          (2, 1, 'U2', 'Accountant', 1, 1),
          (3, 1, 'U3', 'Reader', 1, 1),
          (4, 2, 'U4', 'Other accountant', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 1), (4, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'ACCOUNTANT', 'Accountant'), (11, 'GL_READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'gl.account.read'), (10, 'gl.account.manage'), (10, 'gl.period.read'), (10, 'gl.period.manage'),
          (11, 'gl.account.read'), (11, 'gl.period.read')""");
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

  private static JsonNode account(String body) throws Exception {
    return ok(201, call("POST", "/gl/accounts", body, accountant));
  }

  private static long heading(String no, String type) throws Exception {
    return account("{\"accountNo\":\"" + no + "\",\"name\":\"Heading " + no + "\",\"accountType\":\"" + type
        + "\",\"isHeading\":true}").get("id").asLong();
  }

  private static long posting(String no, String type, Long parentId) throws Exception {
    return account("{\"accountNo\":\"" + no + "\",\"name\":\"Account " + no + "\",\"accountType\":\"" + type
        + "\",\"parentId\":" + parentId + "}").get("id").asLong();
  }

  private static JsonNode getAccount(long id) throws Exception {
    return ok(200, call("GET", "/gl/accounts/" + id, null, reader));
  }

  private static JsonNode year(String startDate) throws Exception {
    return ok(201, call("POST", "/gl/fiscal-years", "{\"startDate\":\"" + startDate + "\"}", accountant));
  }

  // ---- accounts ----

  @Test
  void createAnAccountAndReadItBack() throws Exception {
    long assets = heading("1000", "ASSET");
    HttpResponse<String> r = call("POST", "/gl/accounts", """
        {"accountNo":"1010","name":"Bank","description":"Main account","accountType":"asset","parentId":%d}"""
        .formatted(assets), accountant);
    JsonNode a = ok(201, r);
    long id = a.get("id").asLong();
    assertEquals("/gl/accounts/" + id, r.headers().firstValue("Location").orElseThrow());
    assertEquals("1010", a.get("accountNo").asText());
    assertEquals("ASSET", a.get("accountType").asText());
    assertEquals(assets, a.get("parentId").asLong());
    assertFalse(a.get("isHeading").asBoolean());
    assertTrue(a.get("manualPosting").asBoolean());
    assertTrue(a.get("isEnabled").asBoolean());
    assertEquals(0, a.get("version").asInt());
    assertEquals(2, a.get("createdBy").asLong());
    // The generic GET returns the same row.
    assertEquals(a, getAccount(id));
  }

  @Test
  void genericQueryFiltersAndSorts() throws Exception {
    heading("1000", "ASSET");
    posting("1010", "ASSET", null);
    posting("4000", "REVENUE", null);
    JsonNode page = ok(200, call("QUERY", "/gl/accounts", """
        {"where":{"field":"accountType","op":"equals","value":"ASSET"},"sort":[{"field":"accountNo"}]}""", reader));
    List<String> numbers = new ArrayList<>();
    page.get("data").forEach(row -> numbers.add(row.get("accountNo").asText()));
    assertEquals(List.of("1000", "1010"), numbers);
  }

  @Test
  void createValidation() throws Exception {
    assertEquals(List.of("accountNo:REQUIRED", "accountType:REQUIRED", "name:REQUIRED"),
        errors(call("POST", "/gl/accounts", "{}", accountant)));
    assertEquals(List.of("accountNo:INVALID_FORMAT", "accountType:INVALID_VALUE", "id:READ_ONLY",
        "isHeading:INVALID_TYPE", "nope:UNKNOWN_FIELD", "parentId:INVALID_TYPE", "version:READ_ONLY"),
        errors(call("POST", "/gl/accounts", """
            {"accountNo":"10 10","name":"x","accountType":"MONEY","isHeading":"yes","parentId":"abc","id":5,
             "version":0,"nope":1}""", accountant)));
  }

  @Test
  void accountNumbersAreUniquePerCompany() throws Exception {
    posting("1010", "ASSET", null);
    assertEquals(List.of("accountNo:ALREADY_EXISTS"), errors(call("POST", "/gl/accounts",
        "{\"accountNo\":\"1010\",\"name\":\"Again\",\"accountType\":\"ASSET\"}", accountant)));
    // Another company may use it.
    ok(201, call("POST", "/gl/accounts", "{\"accountNo\":\"1010\",\"name\":\"Theirs\",\"accountType\":\"ASSET\"}",
        outsider));
  }

  @Test
  void parentRules() throws Exception {
    long notHeading = posting("1010", "ASSET", null);
    long theirs = ok(201, call("POST", "/gl/accounts",
        "{\"accountNo\":\"9000\",\"name\":\"Theirs\",\"accountType\":\"ASSET\",\"isHeading\":true}", outsider))
        .get("id").asLong();
    assertEquals(List.of("parentId:NOT_A_HEADING"), errors(call("POST", "/gl/accounts",
        "{\"accountNo\":\"1020\",\"name\":\"x\",\"accountType\":\"ASSET\",\"parentId\":" + notHeading + "}",
        accountant)));
    assertEquals(List.of("parentId:INVALID_REFERENCE"), errors(call("POST", "/gl/accounts",
        "{\"accountNo\":\"1020\",\"name\":\"x\",\"accountType\":\"ASSET\",\"parentId\":" + theirs + "}",
        accountant)));
    assertEquals(List.of("parentId:INVALID_REFERENCE"), errors(call("POST", "/gl/accounts",
        "{\"accountNo\":\"1020\",\"name\":\"x\",\"accountType\":\"ASSET\",\"parentId\":999999}", accountant)));
  }

  @Test
  void patchAnAccount() throws Exception {
    long assets = heading("1000", "ASSET");
    long bank = posting("1010", "ASSET", null);
    JsonNode a = ok(200, call("PATCH", "/gl/accounts/" + bank,
        "{\"version\":0,\"name\":\"Bank - main\",\"parentId\":" + assets + ",\"manualPosting\":false}", accountant));
    assertEquals("Bank - main", a.get("name").asText());
    assertEquals(assets, a.get("parentId").asLong());
    assertFalse(a.get("manualPosting").asBoolean());
    assertEquals(1, a.get("version").asInt());
    // null clears the parent.
    a = ok(200, call("PATCH", "/gl/accounts/" + bank, "{\"version\":1,\"parentId\":null}", accountant));
    assertTrue(a.get("parentId").isNull());
  }

  @Test
  void patchNeedsTheCurrentVersion() throws Exception {
    long bank = posting("1010", "ASSET", null);
    assertEquals(List.of("version:REQUIRED"),
        errors(call("PATCH", "/gl/accounts/" + bank, "{\"name\":\"x\"}", accountant)));
    ok(200, call("PATCH", "/gl/accounts/" + bank, "{\"version\":0,\"name\":\"x\"}", accountant));
    rejected(409, "VERSION_CONFLICT", call("PATCH", "/gl/accounts/" + bank, "{\"version\":0,\"name\":\"y\"}",
        accountant));
  }

  @Test
  void noCycles() throws Exception {
    long top = heading("1000", "ASSET");
    long middle = account("{\"accountNo\":\"1100\",\"name\":\"Middle\",\"accountType\":\"ASSET\",\"isHeading\":true,"
        + "\"parentId\":" + top + "}").get("id").asLong();
    long bottom = account("{\"accountNo\":\"1110\",\"name\":\"Bottom\",\"accountType\":\"ASSET\",\"isHeading\":true,"
        + "\"parentId\":" + middle + "}").get("id").asLong();
    assertEquals(List.of("parentId:CYCLE"),
        errors(call("PATCH", "/gl/accounts/" + top, "{\"version\":0,\"parentId\":" + bottom + "}", accountant)));
    assertEquals(List.of("parentId:CYCLE"),
        errors(call("PATCH", "/gl/accounts/" + top, "{\"version\":0,\"parentId\":" + top + "}", accountant)));
    // Moving a branch elsewhere is fine.
    long other = heading("2000", "ASSET");
    ok(200, call("PATCH", "/gl/accounts/" + middle, "{\"version\":0,\"parentId\":" + other + "}", accountant));
  }

  @Test
  void aHeadingWithChildrenStaysAHeading() throws Exception {
    long assets = heading("1000", "ASSET");
    posting("1010", "ASSET", assets);
    assertEquals(List.of("isHeading:HAS_CHILDREN"),
        errors(call("PATCH", "/gl/accounts/" + assets, "{\"version\":0,\"isHeading\":false}", accountant)));
  }

  @Test
  void accountsAreCompanyPrivate() throws Exception {
    long bank = posting("1010", "ASSET", null);
    rejected(404, "NOT_FOUND", call("GET", "/gl/accounts/" + bank, null, outsider));
    rejected(404, "NOT_FOUND", call("PATCH", "/gl/accounts/" + bank, "{\"version\":0,\"name\":\"x\"}", outsider));
    rejected(404, "NOT_FOUND", call("PATCH", "/gl/accounts/abc", "{\"version\":0}", accountant));
    JsonNode theirs = ok(200, call("QUERY", "/gl/accounts", "{}", outsider));
    assertEquals(0, theirs.get("data").size());
  }

  @Test
  void writesNeedManage() throws Exception {
    long bank = posting("1010", "ASSET", null);
    rejected(403, "PERMISSION_DENIED", call("POST", "/gl/accounts",
        "{\"accountNo\":\"1020\",\"name\":\"x\",\"accountType\":\"ASSET\"}", reader));
    rejected(403, "PERMISSION_DENIED", call("PATCH", "/gl/accounts/" + bank, "{\"version\":0}", reader));
  }

  @Test
  void accountHistory() throws Exception {
    long bank = posting("1010", "ASSET", null);
    ok(200, call("PATCH", "/gl/accounts/" + bank, "{\"version\":0,\"name\":\"Bank - main\",\"isEnabled\":false}",
        accountant));
    JsonNode history = ok(200, call("GET", "/gl/accounts/" + bank + "/history", null, admin)).get("data");
    assertEquals(2, history.size());
    assertEquals("UPDATED", history.get(0).get("action").asText());
    JsonNode changes = history.get(0).get("changes");
    assertEquals("Account 1010", changes.get("name").get("old").asText());
    assertEquals("Bank - main", changes.get("name").get("new").asText());
    assertFalse(changes.get("isEnabled").get("new").asBoolean());
    assertEquals("CREATED", history.get(1).get("action").asText());
    assertEquals("ASSET", history.get(1).get("changes").get("accountType").get("new").asText());
  }

  // ---- fiscal years and periods ----

  @Test
  void aFiscalYearHasTwelveMonthlyPeriods() throws Exception {
    JsonNode y = year("2026-04-01");
    assertEquals("2026-2027", y.get("name").asText());
    assertEquals("2026-04-01", y.get("startDate").asText());
    assertEquals("2027-03-31", y.get("endDate").asText());
    JsonNode periods = y.get("periods");
    assertEquals(12, periods.size());
    assertEquals("2026-04", periods.get(0).get("name").asText());
    assertEquals("2026-04-30", periods.get(0).get("endDate").asText());
    assertEquals("2027-02-28", periods.get(10).get("endDate").asText());
    assertEquals("2027-03", periods.get(11).get("name").asText());
    for (int i = 0; i < 12; i++) {
      assertEquals(i + 1, periods.get(i).get("periodNo").asInt());
      assertEquals("OPEN", periods.get(i).get("status").asText());
      assertEquals(y.get("id").asLong(), periods.get(i).get("fiscalYearId").asLong());
    }
  }

  @Test
  void calendarYearsAreNamedByTheirYear() throws Exception {
    JsonNode y = year("2026-01-01");
    assertEquals("2026", y.get("name").asText());
    assertEquals("2026-12-31", y.get("endDate").asText());
    assertEquals("2027", year("2027-01-01").get("name").asText());
  }

  @Test
  void yearsFollowEachOther() throws Exception {
    year("2026-04-01");
    HttpResponse<String> gap = call("POST", "/gl/fiscal-years", "{\"startDate\":\"2027-05-01\"}", accountant);
    assertEquals(List.of("startDate:NOT_CONTIGUOUS"), errors(gap));
    // the company's language (French), the expected date in words; params keep the value as data
    JsonNode error = MySqlTestStack.json(gap).get("errors").get(0);
    assertEquals("Le nouvel exercice doit commencer le 1 avril 2027, le lendemain de la fin du précédent",
        error.get("message").asText());
    assertEquals("2027-04-01", error.get("params").get("date").asText());
    assertEquals(List.of("startDate:NOT_CONTIGUOUS"),
        errors(call("POST", "/gl/fiscal-years", "{\"startDate\":\"2026-04-01\"}", accountant)));
    year("2027-04-01");
    JsonNode years = ok(200, call("GET", "/gl/fiscal-years", null, reader));
    assertEquals(2, years.size());
    assertEquals("2027-2028", years.get(1).get("name").asText());
    assertEquals(12, years.get(1).get("periods").size());
  }

  @Test
  void fiscalYearValidation() throws Exception {
    assertEquals(List.of("startDate:REQUIRED"), errors(call("POST", "/gl/fiscal-years", "{}", accountant)));
    assertEquals(List.of("startDate:NOT_FIRST_OF_MONTH"),
        errors(call("POST", "/gl/fiscal-years", "{\"startDate\":\"2026-04-15\"}", accountant)));
    assertEquals(List.of("startDate:INVALID_FORMAT", "x:UNKNOWN_FIELD"),
        errors(call("POST", "/gl/fiscal-years", "{\"startDate\":\"April\",\"x\":1}", accountant)));
    rejected(403, "PERMISSION_DENIED", call("POST", "/gl/fiscal-years", "{\"startDate\":\"2026-04-01\"}", reader));
  }

  @Test
  void concurrentFirstYearsCreateOnlyOne() throws Exception {
    List<Integer> statuses = runConcurrently(List.of(
        () -> call("POST", "/gl/fiscal-years", "{\"startDate\":\"2026-01-01\"}", accountant).statusCode(),
        () -> call("POST", "/gl/fiscal-years", "{\"startDate\":\"2026-04-01\"}", accountant).statusCode()));
    assertEquals(List.of(201, 422), statuses.stream().sorted().toList());
    assertEquals(1, ok(200, call("GET", "/gl/fiscal-years", null, reader)).size());
  }

  @Test
  void closeAndReopenAPeriod() throws Exception {
    long id = year("2026-01-01").get("periods").get(0).get("id").asLong();
    JsonNode closed = ok(200, call("POST", "/gl/periods/" + id + "/close", null, accountant));
    assertEquals("CLOSED", closed.get("status").asText());
    assertEquals(2, closed.get("statusChangedBy").asLong());
    rejected(409, "PERIOD_ALREADY_CLOSED", call("POST", "/gl/periods/" + id + "/close", null, accountant));
    // Reopening is its own permission: the accountant role lacks it.
    rejected(403, "PERMISSION_DENIED", call("POST", "/gl/periods/" + id + "/reopen", null, accountant));
    JsonNode reopened = ok(200, call("POST", "/gl/periods/" + id + "/reopen", null, admin));
    assertEquals("OPEN", reopened.get("status").asText());
    rejected(409, "PERIOD_ALREADY_OPEN", call("POST", "/gl/periods/" + id + "/reopen", null, admin));
    assertEquals("OPEN", ok(200, call("GET", "/gl/periods/" + id, null, reader)).get("status").asText());

    JsonNode history = ok(200, call("GET", "/gl/periods/" + id + "/history", null, admin)).get("data");
    List<String> actions = new ArrayList<>();
    history.forEach(e -> actions.add(e.get("action").asText()));
    assertEquals(List.of("REOPENED", "CLOSED"), actions);
    assertEquals("CLOSED", history.get(1).get("changes").get("status").get("new").asText());
    rejected(403, "PERMISSION_DENIED", call("GET", "/gl/periods/" + id + "/history", null, accountant));
  }

  @Test
  void periodsAreCompanyPrivate() throws Exception {
    long id = year("2026-01-01").get("periods").get(0).get("id").asLong();
    rejected(404, "NOT_FOUND", call("GET", "/gl/periods/" + id, null, outsider));
    rejected(404, "NOT_FOUND", call("POST", "/gl/periods/" + id + "/close", null, outsider));
    assertEquals(0, ok(200, call("GET", "/gl/fiscal-years", null, outsider)).size());
    // OTHER starts its own books whenever it likes.
    ok(201, call("POST", "/gl/fiscal-years", "{\"startDate\":\"2025-07-01\"}", outsider));
  }

  private static <T> List<T> runConcurrently(List<Callable<T>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    try {
      List<T> results = new ArrayList<>();
      for (Future<T> f : pool.invokeAll(calls)) {
        results.add(f.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- GET /meta: the hand-written account writes count (docs/ARCHITECTURE.md §23.3) ----

  private static JsonNode metaAccounts(String token) throws Exception {
    for (JsonNode r : ok(200, call("GET", "/meta", null, token)).get("resources")) {
      if (r.get("key").asText().equals("glAccount")) {
        return r;
      }
    }
    return null;
  }

  @Test
  void metaOffersAccountWritesToManagersOnly() throws Exception {
    JsonNode managed = metaAccounts(accountant);
    JsonNode readOnly = metaAccounts(reader);

    assertEquals("gl", managed.get("module").asText());
    assertTrue(managed.get("can").get("create").asBoolean());
    assertTrue(managed.get("can").get("update").asBoolean());
    assertFalse(managed.get("can").get("delete").asBoolean());
    assertFalse(readOnly.get("can").get("create").asBoolean());
    for (JsonNode f : managed.get("fields")) {
      if (f.get("name").asText().equals("accountType")) {
        assertEquals(5, f.get("values").size());
        // the company's language (French by default)
        assertEquals("{\"value\":\"ASSET\",\"label\":\"Actif\"}", f.get("values").get(0).toString());
      }
      if (f.get("name").asText().equals("parentId")) {
        // a self-reference: parent accounts are picked from the accounts
        assertEquals("glAccount", f.get("ref").asText());
      }
    }
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.GeneralLedgerApi.JournalEntryRef;
import com.lesconstructionssapete.stempyerpserver.modules.gl.GeneralLedgerApi.JournalEntryRequest;
import com.lesconstructionssapete.stempyerpserver.modules.gl.GeneralLedgerApi.LineRequest;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * GL reports (docs/ARCHITECTURE.md §22.10 step 4): the trial balance and the
 * account ledger, on a small set of entries whose figures can be checked by
 * hand. Posted once, through GeneralLedgerApi:
 *
 * <pre>
 * 2026-01-10  Dr 1010 Bank      1000.00  STORE 012   Cr 4000 Sales  1000.00  STORE 012
 * 2026-02-05  Dr 5000 Expenses   300.00  STORE 013   Cr 1010 Bank    300.00
 * 2026-02-20  Dr 1020 Cash        50.00              Cr 4000 Sales    50.00
 * 2026-03-15  Dr 5000 Expenses    25.00  STORE 012   Cr 1020 Cash     25.00
 * </pre>
 *
 * Chart: 1000 Assets (heading) over 1010 and 1020; 4000; 5000; 5900 (no
 * lines). Users: U1 administrator, U2 report reader, U3 journal reader only
 * (no report permission), all of ACME; U4 report reader of OTHER.
 */
class GlReportsMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final long ACME = 1;
  private static final long OTHER = 2;
  private static final String TB = "/gl/reports/trial-balance";
  private static final String LEDGER = "/gl/reports/account-ledger";

  private static MySqlTestStack stack;
  private static String admin;
  private static String reporter;
  private static String journalReader;
  private static String outsider;
  private static JournalEntryRef februaryExpense;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(GlReportsMySqlTest.class, AuthSettings.defaults(), GlReportsMySqlTest::seed);
    for (String userNo : new String[] { "U1", "U2", "U3", "U4" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
    admin = stack.login("U1", PASSWORD);
    reporter = stack.login("U2", PASSWORD);
    journalReader = stack.login("U3", PASSWORD);
    outsider = stack.login("U4", PASSWORD);

    ok(201, call("POST", "/gl/fiscal-years", "{\"startDate\":\"2026-01-01\"}", admin));
    long assets = account("{\"accountNo\":\"1000\",\"name\":\"Assets\",\"accountType\":\"ASSET\",\"isHeading\":true}");
    account("{\"accountNo\":\"1010\",\"name\":\"Bank\",\"accountType\":\"ASSET\",\"parentId\":" + assets + "}");
    account("{\"accountNo\":\"1020\",\"name\":\"Cash\",\"accountType\":\"ASSET\",\"parentId\":" + assets + "}");
    account("{\"accountNo\":\"4000\",\"name\":\"Sales\",\"accountType\":\"REVENUE\"}");
    account("{\"accountNo\":\"5000\",\"name\":\"Expenses\",\"accountType\":\"EXPENSE\"}");
    account("{\"accountNo\":\"5900\",\"name\":\"Unused\",\"accountType\":\"EXPENSE\"}");
    long store = ok(201, call("POST", "/gl/dimensions", "{\"code\":\"STORE\",\"name\":\"Stores\"}", admin))
        .get("id").asLong();
    for (String code : new String[] { "012", "013" }) {
      ok(201, call("POST", "/gl/dimension-values",
          "{\"dimensionId\":" + store + ",\"code\":\"" + code + "\",\"name\":\"Store " + code + "\"}", admin));
    }

    post("2026-01-10", "Opening sale", LineRequest.debit("1010", amount("1000")).dimension("STORE", "012"),
        LineRequest.credit("4000", amount("1000")).dimension("STORE", "012"));
    februaryExpense = post("2026-02-05", "Supplies", LineRequest.debit("5000", amount("300")).dimension("STORE", "013")
        .description("Paper"), LineRequest.credit("1010", amount("300")));
    post("2026-02-20", "Cash sale", LineRequest.debit("1020", amount("50")), LineRequest.credit("4000", amount("50")));
    post("2026-03-15", "Petty cash", LineRequest.debit("5000", amount("25")).dimension("STORE", "012"),
        LineRequest.credit("1020", amount("25")));
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
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1),
          (2, 1, 'U2', 'Reporter', 1, 1),
          (3, 1, 'U3', 'Journal reader', 1, 1),
          (4, 2, 'U4', 'Other reporter', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 1), (4, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'REPORTER', 'Reporter'), (11, 'JOURNAL', 'Journal')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'gl.report.read'), (11, 'gl.journal.read')""");
    st.execute("""
        INSERT INTO user_company_roles (user_id, company_id, role_id)
        SELECT 1, 1, id FROM roles WHERE code = 'ADMINISTRATOR'""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (2, 1, 10), (3, 1, 11), (4, 2, 10)");
  }

  // ---- helpers ----

  private static BigDecimal amount(String value) {
    return new BigDecimal(value);
  }

  private static JournalEntryRef post(String date, String description, LineRequest... lines) {
    var request = JournalEntryRequest.of(LocalDate.parse(date), description).source("test.doc", null, null);
    for (LineRequest l : lines) {
      request = request.line(l);
    }
    var finalRequest = request;
    var rc = new RequestContext("seed", 1L, ACME, Set.of());
    return new UnitOfWorkFactory(stack.dataSource()).execute(u -> stack.services().get(GeneralLedgerApi.class)
        .post(u, rc, finalRequest));
  }

  private static HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
    return stack.send(method, path, body, token, token.equals(outsider) ? OTHER : ACME);
  }

  private static JsonNode ok(int status, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static List<String> errors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    List<String> out = new ArrayList<>();
    MySqlTestStack.json(r).get("errors").forEach(e -> out.add(e.get("field").asText() + ":" + e.get("code").asText()));
    return out.stream().sorted().toList();
  }

  private static long account(String body) throws Exception {
    return ok(201, call("POST", "/gl/accounts", body, admin)).get("id").asLong();
  }

  /** "accountNo level opening debits credits closing" per row, the way a person reads a trial balance. */
  private static List<String> rows(JsonNode tb) {
    List<String> out = new ArrayList<>();
    tb.get("rows").forEach(r -> {
      JsonNode a = r.get("amounts");
      out.add(r.get("accountNo").asText() + " " + r.get("level").asInt() + " " + a.get("opening").asText() + " "
          + a.get("debits").asText() + " " + a.get("credits").asText() + " " + a.get("closing").asText());
    });
    return out;
  }

  private static String totals(JsonNode tb) {
    JsonNode t = tb.get("totals");
    return t.get("opening").asText() + " " + t.get("debits").asText() + " " + t.get("credits").asText() + " "
        + t.get("closing").asText();
  }

  // ---- trial balance ----

  @Test
  void februaryTrialBalance() throws Exception {
    JsonNode tb = ok(200, call("GET", TB + "?from=2026-02-01&to=2026-02-28", null, reporter));
    assertEquals("CAD", tb.get("currency").asText());
    assertEquals(List.of(
        "1000 0 1000.00 50.00 300.00 750.00",
        "1010 1 1000.00 0.00 300.00 700.00",
        "1020 1 0.00 50.00 0.00 50.00",
        "4000 0 -1000.00 0.00 50.00 -1050.00",
        "5000 0 0.00 300.00 0.00 300.00"), rows(tb));
    // The books balance: openings and closings sum to zero, debits = credits.
    assertEquals("0.00 350.00 350.00 0.00", totals(tb));
    JsonNode heading = tb.get("rows").get(0);
    assertTrue(heading.get("isHeading").asBoolean());
    assertEquals("ASSET", heading.get("accountType").asText());
  }

  @Test
  void wholeYear() throws Exception {
    JsonNode tb = ok(200, call("GET", TB + "?from=2026-01-01&to=2026-12-31", null, reporter));
    assertEquals(List.of(
        "1000 0 0.00 1050.00 325.00 725.00",
        "1010 1 0.00 1000.00 300.00 700.00",
        "1020 1 0.00 50.00 25.00 25.00",
        "4000 0 0.00 0.00 1050.00 -1050.00",
        "5000 0 0.00 325.00 0.00 325.00"), rows(tb));
    assertEquals("0.00 1375.00 1375.00 0.00", totals(tb));
  }

  @Test
  void zeroAccountsOnRequest() throws Exception {
    JsonNode tb = ok(200, call("GET", TB + "?from=2026-02-01&to=2026-02-28&includeZero=true", null, reporter));
    List<String> rows = rows(tb);
    assertEquals(6, rows.size());
    assertEquals("5900 0 0.00 0.00 0.00 0.00", rows.get(5));
  }

  @Test
  void filteredOnAStore() throws Exception {
    JsonNode s012 = ok(200, call("GET", TB + "?from=2026-02-01&to=2026-03-31&dimension=STORE&value=012", null,
        reporter));
    assertEquals("STORE", s012.get("dimension").asText());
    assertEquals(List.of(
        "1000 0 1000.00 0.00 0.00 1000.00",
        "1010 1 1000.00 0.00 0.00 1000.00",
        "4000 0 -1000.00 0.00 0.00 -1000.00",
        "5000 0 0.00 25.00 0.00 25.00"), rows(s012));
    JsonNode s013 = ok(200, call("GET", TB + "?from=2026-01-01&to=2026-12-31&dimension=store&value=013", null,
        reporter));
    assertEquals(List.of("5000 0 0.00 300.00 0.00 300.00"), rows(s013));
  }

  @Test
  void beforeAnyEntryEverythingIsZero() throws Exception {
    JsonNode tb = ok(200, call("GET", TB + "?from=2025-01-01&to=2025-12-31", null, reporter));
    assertEquals(0, tb.get("rows").size());
    assertEquals("0.00 0.00 0.00 0.00", totals(tb));
  }

  @Test
  void trialBalanceValidation() throws Exception {
    assertEquals(List.of("from:REQUIRED", "to:REQUIRED"), errors(call("GET", TB, null, reporter)));
    assertEquals(List.of("to:BEFORE_FROM"), errors(call("GET", TB + "?from=2026-02-01&to=2026-01-01", null,
        reporter)));
    assertEquals(List.of("value:REQUIRED"), errors(call("GET", TB + "?from=2026-01-01&to=2026-01-31&dimension=STORE",
        null, reporter)));
    assertEquals(List.of("value:UNKNOWN_VALUE"), errors(call("GET",
        TB + "?from=2026-01-01&to=2026-01-31&dimension=STORE&value=999", null, reporter)));
    HttpResponse<String> bad = call("GET", TB + "?from=January&to=2026-01-31", null, reporter);
    assertEquals(400, bad.statusCode(), bad.body());
  }

  @Test
  void permissionsAndCompanies() throws Exception {
    HttpResponse<String> denied = call("GET", TB + "?from=2026-01-01&to=2026-12-31", null, journalReader);
    assertEquals(403, denied.statusCode(), denied.body());
    JsonNode theirs = ok(200, call("GET", TB + "?from=2026-01-01&to=2026-12-31", null, outsider));
    assertEquals(0, theirs.get("rows").size());
    assertEquals(List.of("accountNo:UNKNOWN_ACCOUNT"), errors(call("GET",
        LEDGER + "?accountNo=1010&from=2026-01-01&to=2026-12-31", null, outsider)));
  }

  // ---- account ledger ----

  @Test
  void bankLedgerForTheYear() throws Exception {
    JsonNode l = ok(200, call("GET", LEDGER + "?accountNo=1010&from=2026-01-01&to=2026-12-31", null, reporter));
    assertEquals("1010", l.get("accountNo").asText());
    assertEquals("Bank", l.get("name").asText());
    assertEquals("0.00", l.get("opening").asText());
    assertEquals("1000.00", l.get("debits").asText());
    assertEquals("300.00", l.get("credits").asText());
    assertEquals("700.00", l.get("closing").asText());
    JsonNode lines = l.get("lines");
    assertEquals(2, lines.size());
    assertEquals("2026-01-10", lines.get(0).get("postingDate").asText());
    assertEquals("JE00000001", lines.get(0).get("entryNo").asText());
    assertEquals("Opening sale", lines.get(0).get("description").asText());
    assertEquals("1000.00", lines.get(0).get("balance").asText());
    assertEquals("{\"STORE\":\"012\"}", lines.get(0).get("dimensions").toString());
    assertEquals("300.00", lines.get(1).get("credit").asText());
    assertEquals("700.00", lines.get(1).get("balance").asText());
    assertEquals("test.doc", lines.get(1).get("sourceType").asText());
  }

  @Test
  void ledgerFromAnOpeningBalance() throws Exception {
    JsonNode l = ok(200, call("GET", LEDGER + "?accountNo=1010&from=2026-02-01&to=2026-12-31", null, reporter));
    assertEquals("1000.00", l.get("opening").asText());
    assertEquals(1, l.get("lines").size());
    assertEquals("700.00", l.get("closing").asText());
  }

  @Test
  void lineDescriptionsWinOverTheEntrys() throws Exception {
    JsonNode l = ok(200, call("GET", LEDGER + "?accountNo=5000&from=2026-01-01&to=2026-12-31", null, reporter));
    assertEquals("Paper", l.get("lines").get(0).get("description").asText());
    assertEquals("Petty cash", l.get("lines").get(1).get("description").asText());
  }

  @Test
  void ledgerFilteredOnAStore() throws Exception {
    JsonNode l = ok(200, call("GET", LEDGER + "?accountNo=5000&from=2026-01-01&to=2026-12-31&dimension=STORE&value=012",
        null, reporter));
    assertEquals(1, l.get("lines").size());
    assertEquals("25.00", l.get("closing").asText());
  }

  @Test
  void ledgerValidation() throws Exception {
    assertEquals(List.of("accountNo:HEADING_ACCOUNT"), errors(call("GET",
        LEDGER + "?accountNo=1000&from=2026-01-01&to=2026-12-31", null, reporter)));
    assertEquals(List.of("accountNo:UNKNOWN_ACCOUNT"), errors(call("GET",
        LEDGER + "?accountNo=9999&from=2026-01-01&to=2026-12-31", null, reporter)));
    assertEquals(List.of("from:REQUIRED"), errors(call("GET", LEDGER + "?accountNo=1010&to=2026-12-31", null,
        reporter)));
  }

  @Test
  void aReversalShowsInTheReports() throws Exception {
    // Reverse February's expense on March 1st: the year's expenses drop back to the petty cash.
    ok(201, call("POST", "/gl/journal-entries/" + februaryExpense.id() + "/reverse",
        "{\"postingDate\":\"2026-03-01\"}", admin));
    try {
      JsonNode l = ok(200, call("GET", LEDGER + "?accountNo=5000&from=2026-01-01&to=2026-12-31", null, reporter));
      assertEquals(3, l.get("lines").size());
      assertEquals("25.00", l.get("closing").asText());
      JsonNode tb = ok(200, call("GET", TB + "?from=2026-01-01&to=2026-12-31", null, reporter));
      assertEquals("0.00 1675.00 1675.00 0.00", totals(tb));
    } finally {
      // The other tests check the four entries alone: remove the reversal (a test-only shortcut).
      new UnitOfWorkFactory(stack.dataSource()).run(u -> {
        Jdbc.update(u, "UPDATE gl_journal_entries SET reversed_by_id = NULL WHERE id = ?", februaryExpense.id());
        Jdbc.update(u, """
            DELETE l FROM gl_journal_lines l JOIN gl_journal_entries e ON e.id = l.entry_id
             WHERE e.reversal_of_id = ?""", februaryExpense.id());
        Jdbc.update(u, "DELETE FROM gl_journal_entries WHERE reversal_of_id = ?", februaryExpense.id());
      });
    }
  }
}

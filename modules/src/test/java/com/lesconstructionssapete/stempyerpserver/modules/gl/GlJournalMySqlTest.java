package com.lesconstructionssapete.stempyerpserver.modules.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.GeneralLedgerApi.JournalEntryRequest;
import com.lesconstructionssapete.stempyerpserver.modules.gl.GeneralLedgerApi.LineRequest;
import com.lesconstructionssapete.stempyerpserver.modules.gl.events.JournalEntryPosted;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Journal entries (docs/ARCHITECTURE.md §22.10 step 3): manual posting over
 * HTTP with its checks, periods, dimensions, idempotency keys, gapless
 * numbers, reversals, history, and GeneralLedgerApi as another module uses it.
 *
 * Chart (ACME): 1000 heading; 1010 Bank; 1200 Receivables (control account);
 * 4000 Sales; 5000 Expenses (PROJECT required, STORE not allowed); 5100
 * disabled. Dimensions STORE (012) and PROJECT (P1). Fiscal year 2026.
 *
 * Users: U1 administrator of ACME, U2 accountant of ACME (journal read, post,
 * reverse; accounts manage), U3 reader of ACME, U4 accountant of OTHER.
 */
class GlJournalMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final long ACME = 1;
  private static final long OTHER = 2;
  private static final String PATH = "/gl/journal-entries";

  /** Every JournalEntryPosted, as a listening module would see it. */
  static final List<JournalEntryPosted> EVENTS = new CopyOnWriteArrayList<>();

  static final class ListenerModule implements ERPModule {
    @Override
    public String name() {
      return "test-gl-listener";
    }

    @Override
    public void registerEntities(EntityRegistry entityRegistry) {
    }

    @Override
    public void registerEventHandlers(EventBus eventBus, ServiceRegistry serviceRegistry) {
      eventBus.subscribe(JournalEntryPosted.class, (uow, event) -> EVENTS.add(event));
    }
  }

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static GeneralLedgerApi ledger;
  private static String admin;
  private static String accountant;
  private static String reader;
  private static String outsider;
  private static long bank;
  private static long firstPeriod;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(GlJournalMySqlTest.class, AuthSettings.defaults(), GlJournalMySqlTest::seed,
        List.of(new ListenerModule()));
    for (String userNo : new String[] { "U1", "U2", "U3", "U4" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
    admin = stack.login("U1", PASSWORD);
    accountant = stack.login("U2", PASSWORD);
    reader = stack.login("U3", PASSWORD);
    outsider = stack.login("U4", PASSWORD);
    uow = new UnitOfWorkFactory(stack.dataSource());
    ledger = stack.services().get(GeneralLedgerApi.class);

    // The books, set up through the API like an administrator would.
    firstPeriod = ok(201, call("POST", "/gl/fiscal-years", "{\"startDate\":\"2026-01-01\"}", admin))
        .get("periods").get(0).get("id").asLong();
    long heading = account("{\"accountNo\":\"1000\",\"name\":\"Current assets\",\"accountType\":\"ASSET\","
        + "\"isHeading\":true}");
    bank = account("{\"accountNo\":\"1010\",\"name\":\"Bank\",\"accountType\":\"ASSET\",\"parentId\":" + heading + "}");
    account("{\"accountNo\":\"1200\",\"name\":\"Receivables\",\"accountType\":\"ASSET\",\"manualPosting\":false}");
    account("{\"accountNo\":\"4000\",\"name\":\"Sales\",\"accountType\":\"REVENUE\"}");
    long expenses = account("{\"accountNo\":\"5000\",\"name\":\"Expenses\",\"accountType\":\"EXPENSE\"}");
    account("{\"accountNo\":\"5100\",\"name\":\"Old expenses\",\"accountType\":\"EXPENSE\",\"isEnabled\":false}");
    long store = ok(201, call("POST", "/gl/dimensions", "{\"code\":\"STORE\",\"name\":\"Stores\"}", admin))
        .get("id").asLong();
    long project = ok(201, call("POST", "/gl/dimensions", "{\"code\":\"PROJECT\",\"name\":\"Projects\"}", admin))
        .get("id").asLong();
    ok(201, call("POST", "/gl/dimension-values", "{\"dimensionId\":" + store + ",\"code\":\"012\",\"name\":\"Downtown\"}",
        admin));
    ok(201, call("POST", "/gl/dimension-values", "{\"dimensionId\":" + project + ",\"code\":\"P1\",\"name\":\"Smith\"}",
        admin));
    ok(200, call("PUT", "/gl/accounts/" + expenses + "/dimension-rules",
        "{\"version\":0,\"rules\":{\"PROJECT\":\"REQUIRED\",\"STORE\":\"NOT_ALLOWED\"}}", admin));
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
  void noEntries() {
    uow.run(u -> {
      Jdbc.update(u, "UPDATE gl_journal_entries SET reversed_by_id = NULL");
      Jdbc.update(u, "DELETE FROM gl_journal_lines");
      Jdbc.update(u, "DELETE FROM gl_journal_entries WHERE reversal_of_id IS NOT NULL");
      Jdbc.update(u, "DELETE FROM gl_journal_entries");
      Jdbc.update(u, "DELETE FROM idempotency_keys");
      Jdbc.update(u, "DELETE FROM number_series_counters");
      Jdbc.update(u, "UPDATE gl_periods SET status = 'OPEN'");
    });
    EVENTS.clear();
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
          (10, 'gl.journal.read'), (10, 'gl.journal.post'), (10, 'gl.journal.reverse'),
          (10, 'gl.account.read'), (10, 'gl.account.manage'), (10, 'gl.period.read'), (10, 'gl.period.manage'),
          (11, 'gl.journal.read')""");
    st.execute("""
        INSERT INTO user_company_roles (user_id, company_id, role_id)
        SELECT 1, 1, id FROM roles WHERE code = 'ADMINISTRATOR'""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (2, 1, 10), (3, 1, 11), (4, 2, 10)");
  }

  // ---- helpers ----

  private static HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
    return stack.send(method, path, body, token, token.equals(outsider) ? OTHER : ACME);
  }

  private static HttpResponse<String> post(String body, String idempotencyKey) throws Exception {
    var b = java.net.http.HttpRequest.newBuilder(stack.uri(PATH))
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + accountant);
    if (idempotencyKey != null) {
      b.header("Idempotency-Key", idempotencyKey);
    }
    return java.net.http.HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
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

  private static long account(String body) throws Exception {
    return ok(201, call("POST", "/gl/accounts", body, admin)).get("id").asLong();
  }

  /** A balanced two-line entry: debit {@code debitAccount}, credit the bank. */
  private static String entry(String date, String debitAccount, String amount, String dimensions) {
    return """
        {"postingDate":"%s","description":"Test entry","lines":[
          {"accountNo":"%s","debit":"%s","description":"Expense","dimensions":%s},
          {"accountNo":"1010","credit":"%s"}]}""".formatted(date, debitAccount, amount, dimensions, amount);
  }

  private static JsonNode postOk(String body) throws Exception {
    return ok(201, call("POST", PATH, body, accountant));
  }

  private static long count(String sql) {
    return uow.executeReadOnly(u -> Jdbc.queryFirst(u, sql, rs -> rs.getLong(1)).orElseThrow());
  }

  private static RequestContext moduleContext() {
    return new RequestContext("test-request", 2L, ACME, Set.of());
  }

  // ---- manual posting ----

  @Test
  void postAndReadAnEntry() throws Exception {
    HttpResponse<String> r = call("POST", PATH, entry("2026-01-15", "4000", "100", "{}").replace(
        "\"accountNo\":\"4000\",\"debit\"", "\"accountNo\":\"1200\",\"debit\""), accountant);
    // 1200 is a control account: a manual entry can't use it.
    assertEquals(List.of("lines[0].accountNo:CONTROL_ACCOUNT"), errors(r));

    JsonNode e = postOk("""
        {"postingDate":"2026-01-15","description":"Office supplies","lines":[
          {"accountNo":"5000","debit":"123.45","description":"Paper","dimensions":{"PROJECT":"P1"}},
          {"accountNo":"1010","credit":123.45}]}""");
    assertEquals("JE00000001", e.get("entryNo").asText());
    assertEquals("2026-01-15", e.get("postingDate").asText());
    assertEquals(firstPeriod, e.get("periodId").asLong());
    assertEquals("MANUAL", e.get("sourceType").asText());
    assertEquals("CAD", e.get("currency").asText());
    assertEquals("123.45", e.get("total").asText());
    assertEquals(2, e.get("postedBy").asLong());
    JsonNode lines = e.get("lines");
    assertEquals("5000", lines.get(0).get("accountNo").asText());
    assertEquals("Expenses", lines.get(0).get("accountName").asText());
    assertEquals("123.45", lines.get(0).get("debit").asText());
    assertEquals("0.00", lines.get(0).get("credit").asText());
    assertEquals("{\"PROJECT\":\"P1\"}", lines.get(0).get("dimensions").toString());
    assertEquals("123.45", lines.get(1).get("credit").asText());
    assertEquals("{}", lines.get(1).get("dimensions").toString());
    assertEquals(e, ok(200, call("GET", PATH + "/" + e.get("id").asLong(), null, reader)));
  }

  @Test
  void numbersAreConsecutive() throws Exception {
    assertEquals("JE00000001", postOk(entry("2026-01-10", "4000", "10", "{}")).get("entryNo").asText());
    assertEquals("JE00000002", postOk(entry("2026-01-11", "4000", "20", "{}")).get("entryNo").asText());
  }

  @Test
  void shapeIsChecked() throws Exception {
    assertEquals(List.of("description:REQUIRED", "lines:TOO_FEW_LINES", "postingDate:REQUIRED"),
        errors(call("POST", PATH, "{}", accountant)));
    // A body that doesn't parse is refused before the posting rules run.
    assertEquals(List.of("lines[1].debit:INVALID_TYPE", "x:UNKNOWN_FIELD"),
        errors(call("POST", PATH, """
            {"postingDate":"2026-01-10","description":"x","x":1,"lines":[
              {"accountNo":"4000","debit":"1"},
              {"debit":"lots"}]}""", accountant)));
    assertEquals(List.of("lines[0]:BOTH_SIDES", "lines[1].credit:NEGATIVE", "lines[2]:NO_AMOUNT",
        "lines[3].accountNo:REQUIRED"),
        errors(call("POST", PATH, """
            {"postingDate":"2026-01-10","description":"x","lines":[
              {"accountNo":"4000","debit":"1","credit":"1"},
              {"accountNo":"1010","credit":"-1"},
              {"accountNo":"1010"},
              {"debit":"1"}]}""", accountant)));
  }

  @Test
  void accountsAmountsAndBalanceAreChecked() throws Exception {
    assertEquals(List.of("lines:UNBALANCED", "lines[0].accountNo:HEADING_ACCOUNT", "lines[1].accountNo:ACCOUNT_DISABLED",
        "lines[2].accountNo:UNKNOWN_ACCOUNT", "lines[3].debit:NOT_ROUNDED"),
        errors(call("POST", PATH, """
            {"postingDate":"2026-01-10","description":"x","lines":[
              {"accountNo":"1000","debit":"10"},
              {"accountNo":"5100","debit":"10"},
              {"accountNo":"9999","credit":"10"},
              {"accountNo":"4000","debit":"10.005"}]}""", accountant)));
    // Account numbers match ignoring case.
    postOk(entry("2026-01-10", "4000", "10", "{}").replace("\"1010\"", "\"1010\""));
  }

  @Test
  void dimensionsAndRules() throws Exception {
    assertEquals(List.of("lines[0].dimensions.PROJECT:DIMENSION_REQUIRED", "lines[0].dimensions.STORE:DIMENSION_NOT_ALLOWED"),
        errors(call("POST", PATH, entry("2026-01-10", "5000", "10", "{\"STORE\":\"012\"}"), accountant)));
    assertEquals(List.of("lines[0].dimensions.PROJECT:UNKNOWN_VALUE"),
        errors(call("POST", PATH, entry("2026-01-10", "5000", "10", "{\"PROJECT\":\"P9\"}"), accountant)));
    JsonNode e = postOk(entry("2026-01-10", "4000", "10", "{\"STORE\":\"012\",\"PROJECT\":\"P1\"}"));
    assertEquals("{\"PROJECT\":\"P1\",\"STORE\":\"012\"}", e.get("lines").get(0).get("dimensions").toString());
  }

  @Test
  void periodsAreChecked() throws Exception {
    rejected(409, "PERIOD_NOT_FOUND", call("POST", PATH, entry("2025-12-31", "4000", "10", "{}"), accountant));
    ok(200, call("POST", "/gl/periods/" + firstPeriod + "/close", null, accountant));
    rejected(409, "PERIOD_CLOSED", call("POST", PATH, entry("2026-01-20", "4000", "10", "{}"), accountant));
    // Refused postings take no number: the next one is still the first.
    assertEquals("JE00000001", postOk(entry("2026-02-01", "4000", "10", "{}")).get("entryNo").asText());
  }

  @Test
  void permissionsAndCompanies() throws Exception {
    long id = postOk(entry("2026-01-10", "4000", "10", "{}")).get("id").asLong();
    rejected(403, "PERMISSION_DENIED", call("POST", PATH, entry("2026-01-10", "4000", "10", "{}"), reader));
    rejected(404, "NOT_FOUND", call("GET", PATH + "/" + id, null, outsider));
    rejected(404, "NOT_FOUND", call("POST", PATH + "/" + id + "/reverse", "{\"postingDate\":\"2026-01-11\"}",
        outsider));
    assertEquals(0, ok(200, call("GET", PATH, null, outsider)).get("data").size());
  }

  @Test
  void listFilters() throws Exception {
    postOk(entry("2026-01-10", "4000", "10", "{}"));
    postOk(entry("2026-02-10", "4000", "20", "{}"));
    postOk(entry("2026-03-10", "4000", "30", "{}"));
    JsonNode all = ok(200, call("GET", PATH, null, reader)).get("data");
    assertEquals(3, all.size());
    assertEquals("2026-03-10", all.get(0).get("postingDate").asText());
    assertTrue(all.get(0).get("lines").isNull());
    JsonNode feb = ok(200, call("GET", PATH + "?from=2026-02-01&to=2026-02-28", null, reader)).get("data");
    assertEquals(1, feb.size());
    assertEquals("20.00", feb.get(0).get("total").asText());
    assertEquals(0, ok(200, call("GET", PATH + "?sourceType=sales.invoice", null, reader)).get("data").size());
    rejected(400, "INVALID_PARAMETER", call("GET", PATH + "?from=yesterday", null, reader));
  }

  @Test
  void anAccountWithPostingsKeepsItsType() throws Exception {
    postOk(entry("2026-01-10", "4000", "10", "{}"));
    JsonNode a = ok(200, call("GET", "/gl/accounts/" + bank, null, admin));
    assertEquals(List.of("accountType:HAS_POSTINGS"), errors(call("PATCH", "/gl/accounts/" + bank,
        "{\"version\":" + a.get("version").asInt() + ",\"accountType\":\"LIABILITY\"}", accountant)));
    ok(200, call("PATCH", "/gl/accounts/" + bank, "{\"version\":" + a.get("version").asInt()
        + ",\"name\":\"Bank - main\"}", accountant));
  }

  // ---- idempotency ----

  @Test
  void aRetryWithTheSameKeyReplaysTheEntry() throws Exception {
    String body = entry("2026-01-10", "4000", "10", "{}");
    HttpResponse<String> first = post(body, "key-1");
    HttpResponse<String> second = post(body, "key-1");
    assertEquals(201, first.statusCode(), first.body());
    assertEquals(201, second.statusCode(), second.body());
    assertEquals(MySqlTestStack.json(first).get("id"), MySqlTestStack.json(second).get("id"));
    assertFalse(first.headers().firstValue("Idempotent-Replayed").isPresent());
    assertEquals("true", second.headers().firstValue("Idempotent-Replayed").orElseThrow());
    assertEquals(1, count("SELECT COUNT(*) FROM gl_journal_entries"));
    // A different key posts again.
    assertEquals(201, post(body, "key-2").statusCode());
    assertEquals(2, count("SELECT COUNT(*) FROM gl_journal_entries"));
  }

  @Test
  void aKeyIsForOneRequest() throws Exception {
    post(entry("2026-01-10", "4000", "10", "{}"), "key-1");
    rejected(409, "IDEMPOTENCY_KEY_REUSED", post(entry("2026-01-10", "4000", "99", "{}"), "key-1"));
    assertEquals(List.of("Idempotency-Key:INVALID_FORMAT"), errors(post(entry("2026-01-10", "4000", "10", "{}"),
        "has spaces")));
  }

  @Test
  void aRefusedRequestDoesNotKeepItsKey() throws Exception {
    HttpResponse<String> refused = post(entry("2025-12-31", "4000", "10", "{}"), "key-1");
    assertEquals(409, refused.statusCode());
    // Same key, corrected body: the first attempt left nothing behind.
    assertEquals(201, post(entry("2026-01-10", "4000", "10", "{}"), "key-1").statusCode());
  }

  @Test
  void concurrentRetriesPostOnce() throws Exception {
    String body = entry("2026-01-10", "4000", "10", "{}");
    List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      calls.add(() -> post(body, "key-c"));
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<HttpResponse<String>> responses = new ArrayList<>();
    try {
      for (Future<HttpResponse<String>> f : pool.invokeAll(calls)) {
        responses.add(f.get());
      }
    } finally {
      pool.shutdownNow();
    }
    Set<String> ids = new java.util.HashSet<>();
    int replayed = 0;
    for (HttpResponse<String> r : responses) {
      assertEquals(201, r.statusCode(), r.body());
      ids.add(MySqlTestStack.json(r).get("id").asText());
      replayed += r.headers().firstValue("Idempotent-Replayed").isPresent() ? 1 : 0;
    }
    assertEquals(1, ids.size());
    assertEquals(3, replayed);
    assertEquals(1, count("SELECT COUNT(*) FROM gl_journal_entries"));
  }

  // ---- reversal ----

  @Test
  void reverseAnEntry() throws Exception {
    JsonNode original = postOk(entry("2026-01-10", "4000", "75.50", "{\"STORE\":\"012\"}"));
    long id = original.get("id").asLong();
    HttpResponse<String> r = call("POST", PATH + "/" + id + "/reverse", "{\"postingDate\":\"2026-02-01\"}",
        accountant);
    JsonNode reversal = ok(201, r);
    assertEquals("JE00000002", reversal.get("entryNo").asText());
    assertEquals("Reversal of JE00000001", reversal.get("description").asText());
    assertEquals(id, reversal.get("reversalOfId").asLong());
    assertEquals("75.50", reversal.get("lines").get(0).get("credit").asText());
    assertEquals("0.00", reversal.get("lines").get(0).get("debit").asText());
    assertEquals("{\"STORE\":\"012\"}", reversal.get("lines").get(0).get("dimensions").toString());
    assertEquals("75.50", reversal.get("lines").get(1).get("debit").asText());
    assertEquals(reversal.get("id").asLong(), ok(200, call("GET", PATH + "/" + id, null, reader))
        .get("reversedById").asLong());

    rejected(409, "ALREADY_REVERSED", call("POST", PATH + "/" + id + "/reverse",
        "{\"postingDate\":\"2026-02-01\"}", accountant));
    rejected(409, "CANNOT_REVERSE_A_REVERSAL", call("POST", PATH + "/" + reversal.get("id").asLong() + "/reverse",
        "{\"postingDate\":\"2026-02-01\"}", accountant));
  }

  @Test
  void reversalDatesAndPermissions() throws Exception {
    long id = postOk(entry("2026-01-10", "4000", "10", "{}")).get("id").asLong();
    ok(200, call("POST", "/gl/periods/" + firstPeriod + "/close", null, accountant));
    // Into the closed period: no. The entry itself being in it: fine.
    rejected(409, "PERIOD_CLOSED", call("POST", PATH + "/" + id + "/reverse", "{\"postingDate\":\"2026-01-31\"}",
        accountant));
    assertEquals(List.of("postingDate:REQUIRED"), errors(call("POST", PATH + "/" + id + "/reverse", "{}",
        accountant)));
    rejected(403, "PERMISSION_DENIED", call("POST", PATH + "/" + id + "/reverse",
        "{\"postingDate\":\"2026-02-01\"}", reader));
    JsonNode reversal = ok(201, call("POST", PATH + "/" + id + "/reverse",
        "{\"postingDate\":\"2026-02-01\",\"description\":\"Wrong account\"}", accountant));
    assertEquals("Wrong account", reversal.get("description").asText());
  }

  @Test
  void history() throws Exception {
    long id = postOk(entry("2026-01-10", "4000", "10", "{}")).get("id").asLong();
    ok(201, call("POST", PATH + "/" + id + "/reverse", "{\"postingDate\":\"2026-02-01\"}", accountant));
    JsonNode history = ok(200, call("GET", PATH + "/" + id + "/history", null, admin)).get("data");
    List<String> actions = new ArrayList<>();
    history.forEach(h -> actions.add(h.get("action").asText()));
    assertEquals(List.of("REVERSED", "POSTED"), actions);
    assertEquals("JE00000002", history.get(0).get("changes").get("reversedBy").get("new").asText());
    assertEquals("JE00000001", history.get(1).get("changes").get("entryNo").get("new").asText());
    rejected(403, "PERMISSION_DENIED", call("GET", PATH + "/" + id + "/history", null, accountant));
  }

  // ---- GeneralLedgerApi, as another module uses it ----

  @Test
  void aModulePostsInItsOwnTransaction() {
    var request = JournalEntryRequest.of(LocalDate.of(2026, 3, 5), "Invoice INV-7")
        .source("sales.invoice", 7L, "INV-7")
        .line(LineRequest.debit("1200", new BigDecimal("113.00")).dimension("STORE", "012"))
        .line(LineRequest.credit("4000", new BigDecimal("100.00")).dimension("STORE", "012"))
        .line(LineRequest.credit("1010", new BigDecimal("13.00")).description("tax"));
    var ref = uow.execute(u -> ledger.post(u, moduleContext(), request));
    assertEquals("JE00000001", ref.entryNo());
    assertEquals(1, EVENTS.size());
    JournalEntryPosted event = EVENTS.get(0);
    assertEquals(ref.id(), event.entryId());
    assertEquals("sales.invoice", event.sourceType());
    assertEquals(7L, event.sourceId());
    assertEquals(0, new BigDecimal("113.00").compareTo(event.total()));
  }

  @Test
  void aRolledBackDocumentLeavesNoEntryAndNoGap() {
    var request = JournalEntryRequest.of(LocalDate.of(2026, 3, 5), "Invoice INV-8")
        .source("sales.invoice", 8L, "INV-8")
        .line(LineRequest.debit("1200", new BigDecimal("10.00")))
        .line(LineRequest.credit("4000", new BigDecimal("10.00")));
    try (UnitOfWork u = uow.begin()) {
      assertEquals("JE00000001", ledger.post(u, moduleContext(), request).entryNo());
      u.rollback();
    }
    assertEquals(0, count("SELECT COUNT(*) FROM gl_journal_entries"));
    assertEquals("JE00000001", uow.execute(u -> ledger.post(u, moduleContext(), request)).entryNo());
  }

  @Test
  void theApiReportsProblemsAsFieldErrors() {
    var request = JournalEntryRequest.of(LocalDate.of(2026, 3, 5), "Bad")
        .line(LineRequest.debit("1200", new BigDecimal("10.00")))
        .line(LineRequest.credit("4000", new BigDecimal("9.00")));
    ValidationFailedException e = assertThrows(ValidationFailedException.class,
        () -> uow.execute(u -> ledger.post(u, moduleContext(), request)));
    assertEquals(List.of("lines:UNBALANCED", "sourceType:REQUIRED"),
        e.fieldErrors().stream().map(f -> f.field() + ":" + f.code()).sorted().toList());
  }
}

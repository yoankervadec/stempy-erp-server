package com.lesconstructionssapete.stempyerpserver.modules.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.DescribeDimensionSetOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindOrCreateDimensionSetOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListDimensionRulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ReplaceDimensionRulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ResolveDimensionsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;

/**
 * GL dimensions (docs/ARCHITECTURE.md §22.10 step 2): dimensions and values
 * through the generic routes, account dimension rules over HTTP, and the
 * dimension-set resolver posting will use (called directly: it has no route).
 *
 * Users: U1 administrator of ACME, U2 accountant of ACME (accounts +
 * dimensions: read and write), U3 reader of ACME, U4 accountant of OTHER.
 */
class GlDimensionsMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final long ACME = 1;
  private static final long OTHER = 2;

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static DimensionService dimensions;
  private static String admin;
  private static String accountant;
  private static String reader;
  private static String outsider;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(GlDimensionsMySqlTest.class, AuthSettings.defaults(), GlDimensionsMySqlTest::seed);
    for (String userNo : new String[] { "U1", "U2", "U3", "U4" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
    admin = stack.login("U1", PASSWORD);
    accountant = stack.login("U2", PASSWORD);
    reader = stack.login("U3", PASSWORD);
    outsider = stack.login("U4", PASSWORD);
    uow = new UnitOfWorkFactory(stack.dataSource());
    // Built like GlModule does; the resolver has no route to call it through.
    var accounts = new AccountRepository();
    var repo = new DimensionRepository();
    dimensions = new DimensionService(uow, stack.services().get(PermissionChecker.class), Clock.systemUTC(),
        new FindAccountOperation(accounts), new ListDimensionRulesOperation(repo),
        new ReplaceDimensionRulesOperation(accounts, repo, stack.services().get(AuditLog.class)),
        new ResolveDimensionsOperation(repo), new FindOrCreateDimensionSetOperation(repo),
        new DescribeDimensionSetOperation(repo));
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
      Jdbc.update(u, "DELETE FROM gl_dimension_set_entries");
      Jdbc.update(u, "DELETE FROM gl_dimension_sets");
      Jdbc.update(u, "DELETE FROM gl_account_dimension_rules");
      Jdbc.update(u, "DELETE FROM gl_dimension_values");
      Jdbc.update(u, "DELETE FROM gl_dimensions");
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
          (10, 'gl.account.read'), (10, 'gl.account.manage'),
          (10, 'gl.dimension.read'), (10, 'gl.dimension.create'), (10, 'gl.dimension.update'),
          (11, 'gl.account.read'), (11, 'gl.dimension.read')""");
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

  private static long dimension(String code) throws Exception {
    return ok(201, call("POST", "/gl/dimensions", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}",
        accountant)).get("id").asLong();
  }

  private static long value(long dimensionId, String code) throws Exception {
    return ok(201, call("POST", "/gl/dimension-values",
        "{\"dimensionId\":" + dimensionId + ",\"code\":\"" + code + "\",\"name\":\"Value " + code + "\"}",
        accountant)).get("id").asLong();
  }

  private static long account(String no) throws Exception {
    return ok(201, call("POST", "/gl/accounts",
        "{\"accountNo\":\"" + no + "\",\"name\":\"Account " + no + "\",\"accountType\":\"EXPENSE\"}", accountant))
        .get("id").asLong();
  }

  private static Map<String, String> codes(String... pairs) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      m.put(pairs[i], pairs[i + 1]);
    }
    return m;
  }

  private static List<String> resolveErrors(Map<String, String> codes) {
    ValidationFailedException e = assertThrows(ValidationFailedException.class,
        () -> dimensions.resolveSet(ACME, codes, "lines[0].dimensions"));
    return e.fieldErrors().stream().map(f -> f.field() + ":" + f.code()).sorted().toList();
  }

  // ---- dimensions and values (generic routes) ----

  @Test
  void createDimensionsAndValues() throws Exception {
    long store = dimension("STORE");
    JsonNode d = ok(200, call("GET", "/gl/dimensions/" + store, null, reader));
    assertEquals("STORE", d.get("code").asText());
    assertTrue(d.get("isEnabled").asBoolean());
    long downtown = value(store, "012");
    JsonNode v = ok(200, call("GET", "/gl/dimension-values/" + downtown, null, reader));
    assertEquals(store, v.get("dimensionId").asLong());
    assertEquals("012", v.get("code").asText());
  }

  @Test
  void dimensionCodes() throws Exception {
    dimension("STORE");
    assertEquals(List.of("code:ALREADY_EXISTS"), errors(call("POST", "/gl/dimensions",
        "{\"code\":\"STORE\",\"name\":\"Again\"}", accountant)));
    assertTrue(errors(call("POST", "/gl/dimensions", "{\"code\":\"store-no\",\"name\":\"x\"}", accountant))
        .stream().allMatch(e -> e.startsWith("code:")));
    // Another company has its own.
    ok(201, call("POST", "/gl/dimensions", "{\"code\":\"STORE\",\"name\":\"Theirs\"}", outsider));
  }

  @Test
  void codesCantChangeButNamesCan() throws Exception {
    long store = dimension("STORE");
    assertTrue(errors(call("PATCH", "/gl/dimensions/" + store, "{\"version\":0,\"code\":\"SHOP\"}", accountant))
        .stream().allMatch(e -> e.startsWith("code:")));
    JsonNode d = ok(200, call("PATCH", "/gl/dimensions/" + store,
        "{\"version\":0,\"name\":\"Stores\",\"isEnabled\":false}", accountant));
    assertEquals("Stores", d.get("name").asText());
    long v = value(store, "012");
    assertTrue(errors(call("PATCH", "/gl/dimension-values/" + v, "{\"version\":0,\"code\":\"013\"}", accountant))
        .stream().allMatch(e -> e.startsWith("code:")));
  }

  @Test
  void valueCodesAreUniquePerDimension() throws Exception {
    long store = dimension("STORE");
    long project = dimension("PROJECT");
    value(store, "001");
    value(project, "001"); // the same code in another dimension
    rejected(409, "DUPLICATE_VALUE", call("POST", "/gl/dimension-values",
        "{\"dimensionId\":" + store + ",\"code\":\"001\",\"name\":\"Again\"}", accountant));
  }

  @Test
  void valuesBelongToTheCompanysDimensions() throws Exception {
    long theirs = ok(201, call("POST", "/gl/dimensions", "{\"code\":\"STORE\",\"name\":\"Theirs\"}", outsider))
        .get("id").asLong();
    assertEquals(List.of("dimensionId:INVALID_REFERENCE"), errors(call("POST", "/gl/dimension-values",
        "{\"dimensionId\":" + theirs + ",\"code\":\"001\",\"name\":\"x\"}", accountant)));
    rejected(404, "NOT_FOUND", call("GET", "/gl/dimensions/" + theirs, null, reader));
  }

  @Test
  void queryValuesByDimensionCode() throws Exception {
    long store = dimension("STORE");
    long project = dimension("PROJECT");
    value(store, "012");
    value(store, "001");
    value(project, "P1");
    JsonNode page = ok(200, call("QUERY", "/gl/dimension-values", """
        {"where":{"field":"dimension.code","op":"equals","value":"STORE"},"sort":[{"field":"code"}]}""", reader));
    List<String> found = new ArrayList<>();
    page.get("data").forEach(r -> found.add(r.get("code").asText()));
    assertEquals(List.of("001", "012"), found);
  }

  @Test
  void dimensionsAreNeverDeletedAndNeedPermissions() throws Exception {
    long store = dimension("STORE");
    assertNotEquals(204, call("DELETE", "/gl/dimensions/" + store + "?version=0", null, admin).statusCode());
    rejected(403, "PERMISSION_DENIED", call("POST", "/gl/dimensions", "{\"code\":\"X\",\"name\":\"x\"}", reader));
    rejected(403, "PERMISSION_DENIED", call("PATCH", "/gl/dimensions/" + store, "{\"version\":0,\"name\":\"x\"}",
        reader));
  }

  // ---- account rules ----

  @Test
  void replaceAccountRules() throws Exception {
    dimension("STORE");
    dimension("PROJECT");
    long acct = account("5000");
    assertEquals(0, ok(200, call("GET", "/gl/accounts/" + acct + "/dimension-rules", null, reader)).size());

    JsonNode rules = ok(200, call("PUT", "/gl/accounts/" + acct + "/dimension-rules",
        "{\"version\":0,\"rules\":{\"STORE\":\"not_allowed\",\"PROJECT\":\"REQUIRED\"}}", accountant));
    assertEquals("[{\"dimensionCode\":\"PROJECT\",\"rule\":\"REQUIRED\"},"
        + "{\"dimensionCode\":\"STORE\",\"rule\":\"NOT_ALLOWED\"}]", rules.toString());
    // Rules are part of the account: its version moved.
    assertEquals(1, ok(200, call("GET", "/gl/accounts/" + acct, null, reader)).get("version").asInt());
    rejected(409, "VERSION_CONFLICT", call("PUT", "/gl/accounts/" + acct + "/dimension-rules",
        "{\"version\":0,\"rules\":{}}", accountant));
    // The same rules again change nothing.
    ok(200, call("PUT", "/gl/accounts/" + acct + "/dimension-rules",
        "{\"version\":1,\"rules\":{\"PROJECT\":\"REQUIRED\",\"STORE\":\"NOT_ALLOWED\"}}", accountant));
    assertEquals(1, ok(200, call("GET", "/gl/accounts/" + acct, null, reader)).get("version").asInt());
    // {} removes them all.
    assertEquals(0, ok(200, call("PUT", "/gl/accounts/" + acct + "/dimension-rules",
        "{\"version\":1,\"rules\":{}}", accountant)).size());

    JsonNode history = ok(200, call("GET", "/gl/accounts/" + acct + "/history", null, admin)).get("data");
    assertEquals("UPDATED", history.get(0).get("action").asText());
    JsonNode removed = history.get(0).get("changes").get("dimensionRules");
    // The audit column is MySQL JSON, which reorders keys: compare as objects.
    assertEquals(new ObjectMapper().readTree(
        "{\"PROJECT\":\"REQUIRED\",\"STORE\":\"NOT_ALLOWED\"}"), removed.get("old"));
    assertEquals("{}", removed.get("new").toString());
  }

  @Test
  void accountRulesValidation() throws Exception {
    dimension("STORE");
    long acct = account("5000");
    String path = "/gl/accounts/" + acct + "/dimension-rules";
    assertEquals(List.of("rules:REQUIRED", "version:REQUIRED"), errors(call("PUT", path, "{}", accountant)));
    assertEquals(List.of("rules.STORE:INVALID_VALUE", "x:UNKNOWN_FIELD"),
        errors(call("PUT", path, "{\"version\":0,\"rules\":{\"STORE\":\"MAYBE\"},\"x\":1}", accountant)));
    assertEquals(List.of("rules.NOPE:UNKNOWN_DIMENSION"),
        errors(call("PUT", path, "{\"version\":0,\"rules\":{\"NOPE\":\"REQUIRED\"}}", accountant)));
    rejected(403, "PERMISSION_DENIED", call("PUT", path, "{\"version\":0,\"rules\":{}}", reader));
    rejected(404, "NOT_FOUND", call("GET", path, null, outsider));
  }

  // ---- dimension sets ----

  @Test
  void aCombinationIsStoredOnceAndReused() throws Exception {
    long store = dimension("STORE");
    long project = dimension("PROJECT");
    value(store, "012");
    value(store, "013");
    value(project, "P1");

    long first = dimensions.resolveSet(ACME, codes("STORE", "012", "PROJECT", "P1"), "d").orElseThrow();
    assertEquals(first, dimensions.resolveSet(ACME, codes("PROJECT", "P1", "STORE", "012"), "d").orElseThrow());
    assertEquals(first, dimensions.resolveSet(ACME, codes("store", "012", "PROJECT", "p1"), "d").orElseThrow());
    long storeOnly = dimensions.resolveSet(ACME, codes("STORE", "012"), "d").orElseThrow();
    long otherStore = dimensions.resolveSet(ACME, codes("STORE", "013", "PROJECT", "P1"), "d").orElseThrow();
    assertEquals(3, Set.of(first, storeOnly, otherStore).size());
    assertEquals(Optional.empty(), dimensions.resolveSet(ACME, Map.of(), "d"));

    assertEquals(codes("PROJECT", "P1", "STORE", "012"), dimensions.describeSet(first));
    assertEquals(3L, (long) uow.executeReadOnly(u -> Jdbc.queryFirst(u, "SELECT COUNT(*) FROM gl_dimension_sets",
        rs -> rs.getLong(1)).orElseThrow()));
  }

  @Test
  void unknownOrDisabledCodesAreReported() throws Exception {
    long store = dimension("STORE");
    long project = dimension("PROJECT");
    value(store, "012");
    long off = value(store, "099");
    ok(200, call("PATCH", "/gl/dimension-values/" + off, "{\"version\":0,\"isEnabled\":false}", accountant));
    ok(200, call("PATCH", "/gl/dimensions/" + project, "{\"version\":0,\"isEnabled\":false}", accountant));

    assertEquals(List.of("lines[0].dimensions.NOPE:UNKNOWN_DIMENSION", "lines[0].dimensions.STORE:UNKNOWN_VALUE"),
        resolveErrors(codes("STORE", "777", "NOPE", "1")));
    assertEquals(List.of("lines[0].dimensions.PROJECT:DIMENSION_DISABLED", "lines[0].dimensions.STORE:VALUE_DISABLED"),
        resolveErrors(codes("STORE", "099", "PROJECT", "P1")));
    assertEquals(List.of("lines[0].dimensions.store:DUPLICATE_DIMENSION"),
        resolveErrors(codes("STORE", "012", "store", "012")));
  }

  @Test
  void anotherCompanysValuesAreUnknown() throws Exception {
    long theirs = ok(201, call("POST", "/gl/dimensions", "{\"code\":\"STORE\",\"name\":\"Theirs\"}", outsider))
        .get("id").asLong();
    ok(201, call("POST", "/gl/dimension-values", "{\"dimensionId\":" + theirs + ",\"code\":\"012\",\"name\":\"x\"}",
        outsider));
    assertEquals(List.of("lines[0].dimensions.STORE:UNKNOWN_DIMENSION"), resolveErrors(codes("STORE", "012")));
    assertTrue(dimensions.resolveSet(OTHER, codes("STORE", "012"), "d").isPresent());
  }

  @Test
  void concurrentFirstUsesShareOneSet() throws Exception {
    long store = dimension("STORE");
    long project = dimension("PROJECT");
    value(store, "012");
    value(project, "P1");
    List<Callable<Long>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      calls.add(() -> dimensions.resolveSet(ACME, codes("STORE", "012", "PROJECT", "P1"), "d").orElseThrow());
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    Set<Long> ids = new TreeSet<>();
    try {
      for (Future<Long> f : pool.invokeAll(calls)) {
        ids.add(f.get());
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, ids.size());
    long entries = uow.executeReadOnly(u -> Jdbc.queryFirst(u, "SELECT COUNT(*) FROM gl_dimension_set_entries",
        rs -> rs.getLong(1)).orElseThrow());
    assertEquals(2, entries);
  }

  @Test
  void fieldErrorsCarryAMessage() throws Exception {
    dimension("STORE");
    ValidationFailedException e = assertThrows(ValidationFailedException.class,
        () -> dimensions.resolveSet(ACME, codes("STORE", "nope"), "lines[3].dimensions"));
    FieldError fe = e.fieldErrors().get(0);
    assertEquals("lines[3].dimensions.STORE", fe.field());
    assertTrue(fe.message().contains("nope"), fe.message());
  }

  // ---- a derived layout on real data (docs/ARCHITECTURE.md §23.7) ----

  @Test
  void aDimensionsDerivedPageListsItsValues() throws Exception {
    JsonNode dimension = null;
    for (JsonNode r : ok(200, call("GET", "/meta", null, reader)).get("resources")) {
      if (r.get("key").asText().equals("glDimension")) {
        dimension = r;
      }
    }
    JsonNode layout = dimension.get("layout");

    assertTrue(layout.get("derived").asBoolean());
    assertEquals("[\"code\",\"name\",\"isEnabled\"]", layout.get("sections").get(0).get("fields").toString());
    JsonNode values = layout.get("collections").get(0);
    assertEquals("Valeurs de dimension", values.get("label").asText());
    assertEquals("{\"resource\":\"glDimensionValue\",\"field\":\"dimensionId\"}", values.get("source").toString());
    assertEquals("page", dimension.get("presentation").asText());
  }
}

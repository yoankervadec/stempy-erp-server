package com.lesconstructionssapete.stempyerpserver.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;

/**
 * DocumentEndpoints (docs/ARCHITECTURE.md §23.8) against MySQL, on a test
 * order with lines and payments: written whole, recalculated by its rules,
 * checked on paths like lines[2].quantity, one version and one history entry
 * per save.
 */
class DocumentRoutesMySqlTest {

  private static final String PASSWORD = "test-password";
  private static final String PATH = TestDocumentsModule.PATH;

  private static MySqlTestStack stack;
  private static String clerk; // every test.order permission + histories
  private static String reader; // test.order.read

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(DocumentRoutesMySqlTest.class, AuthSettings.defaults(),
        DocumentRoutesMySqlTest::seed, List.of(new TestDocumentsModule()));
    for (String u : List.of("U1", "U2")) {
      stack.users().setPassword(u, PASSWORD.toCharArray());
    }
    clerk = stack.login("U1", PASSWORD);
    reader = stack.login("U2", PASSWORD);
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static final String STANDARD = """
        `id` BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
        `company_id` BIGINT UNSIGNED NOT NULL,
        `version` INT UNSIGNED NOT NULL DEFAULT 0,
        `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        `created_by` BIGINT UNSIGNED NOT NULL,
        `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        `updated_by` BIGINT UNSIGNED NOT NULL,
        `deleted_at` TIMESTAMP NULL DEFAULT NULL,""";

  private static void seed(Statement st) throws SQLException {
    st.execute("CREATE TABLE test_orders (" + STANDARD + """
          `order_no` VARCHAR(20) NOT NULL,
          `customer` VARCHAR(100) NULL,
          `status` VARCHAR(10) NOT NULL DEFAULT 'DRAFT',
          `total` DECIMAL(19, 2) NOT NULL DEFAULT 0,
          UNIQUE KEY (`company_id`, `order_no`))""");
    st.execute("CREATE TABLE test_order_lines (" + STANDARD + """
          `order_id` BIGINT UNSIGNED NOT NULL,
          `description` VARCHAR(100) NULL,
          `quantity` DECIMAL(19, 3) NOT NULL,
          `unit_price` DECIMAL(19, 2) NOT NULL,
          `line_amount` DECIMAL(19, 2) NOT NULL DEFAULT 0,
          FOREIGN KEY (`order_id`) REFERENCES `test_orders` (`id`))""");
    st.execute("CREATE TABLE test_payments (" + STANDARD + """
          `order_id` BIGINT UNSIGNED NOT NULL,
          `amount` DECIMAL(19, 2) NOT NULL,
          FOREIGN KEY (`order_id`) REFERENCES `test_orders` (`id`))""");
    st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Clerk', 1, 1), (2, 1, 'U2', 'Reader', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'CLERK', 'Clerk'), (11, 'READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'test.order.read'), (10, 'test.order.create'), (10, 'test.order.update'),
          (10, 'test.order.delete'), (10, 'audit.history.read'), (11, 'test.order.read')""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 10), (2, 1, 11)");
  }

  // ---- helpers ----

  private static HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
    return stack.send(method, path, body, token);
  }

  private static JsonNode ok(int status, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  /** A new order: two lines (2 x 1.25, 3.5 x 0.10) and one payment of 1.00. */
  private static JsonNode order(String orderNo) throws Exception {
    return ok(201, send("POST", PATH, """
        {"orderNo": "%s", "customer": "ACME",
         "lines": [{"description": "Bolt", "quantity": "2", "unitPrice": "1.25"},
                   {"description": "Nut", "quantity": "3.5", "unitPrice": "0.10"}],
         "payments": [{"amount": "1.00"}]}""".formatted(orderNo), clerk));
  }

  private static List<String> errors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    List<String> out = new ArrayList<>();
    MySqlTestStack.json(r).get("errors").forEach(e -> out.add(e.get("field").asText() + ":" + e.get("code").asText()));
    return out.stream().sorted().toList();
  }

  private static long count(String sql) throws SQLException {
    try (Connection c = stack.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  // ---- create and read ----

  @Test
  void aDocumentIsSavedWholeAndComputedByItsRules() throws Exception {
    HttpResponse<String> r = send("POST", PATH, """
        {"orderNo": "O-1", "customer": "ACME",
         "lines": [{"description": "Bolt", "quantity": "2", "unitPrice": "1.25"},
                   {"description": "Nut", "quantity": "3.5", "unitPrice": "0.10"}],
         "payments": [{"amount": "1.00"}]}""", clerk);
    JsonNode doc = ok(201, r);
    long id = doc.get("id").asLong();

    assertEquals(PATH + "/" + id, r.headers().firstValue("Location").orElseThrow());
    assertEquals("2.85", doc.get("total").asText());
    assertEquals("DRAFT", doc.get("status").asText());
    JsonNode lines = doc.get("lines");
    assertEquals(2, lines.size());
    assertEquals("2.50", lines.get(0).get("lineAmount").asText());
    assertEquals("0.35", lines.get(1).get("lineAmount").asText());
    assertEquals(id, lines.get(0).get("orderId").asLong());
    assertEquals("1.00", doc.get("payments").get(0).get("amount").asText());
    // the same as GET
    assertEquals(doc, ok(200, send("GET", PATH + "/" + id, null, clerk)));
  }

  @Test
  void aBlankDocumentHasItsDefaultsAndNoLines() throws Exception {
    JsonNode blank = ok(200, send("GET", PATH + "/new", null, clerk));

    assertEquals("DRAFT", blank.get("status").asText());
    assertEquals("[]", blank.get("lines").toString());
    assertEquals("[]", blank.get("payments").toString());
    assertTrue(blank.get("_meta").get("new").asBoolean());
  }

  // ---- update ----

  @Test
  void linesWithAnIdChangeWithoutOneAreAddedMissingOnesGo() throws Exception {
    JsonNode doc = order("O-2");
    long id = doc.get("id").asLong();
    long bolt = doc.get("lines").get(0).get("id").asLong();
    long nut = doc.get("lines").get(1).get("id").asLong();

    JsonNode after = ok(200, send("PATCH", PATH + "/" + id, """
        {"version": 0, "lines": [{"id": %d, "quantity": "4"},
                                 {"description": "Washer", "quantity": "10", "unitPrice": "0.05"}]}"""
        .formatted(bolt), clerk));

    assertEquals(1, after.get("version").asInt());
    JsonNode lines = after.get("lines");
    assertEquals(2, lines.size());
    assertEquals(bolt, lines.get(0).get("id").asLong());
    assertEquals("Bolt", lines.get(0).get("description").asText()); // kept: only quantity was sent
    assertEquals("5.00", lines.get(0).get("lineAmount").asText());
    assertEquals("0.50", lines.get(1).get("lineAmount").asText());
    assertEquals("5.50", after.get("total").asText());
    // the nut is soft-deleted, the payment untouched (its collection was left out)
    assertEquals(1, count("SELECT COUNT(*) FROM test_order_lines WHERE id = " + nut + " AND deleted_at IS NOT NULL"));
    assertEquals(1, after.get("payments").size());
  }

  @Test
  void aCollectionLeftOutKeepsItsLines() throws Exception {
    JsonNode doc = order("O-3");
    long id = doc.get("id").asLong();

    JsonNode after = ok(200, send("PATCH", PATH + "/" + id, "{\"version\": 0, \"customer\": \"Other\"}", clerk));

    assertEquals("Other", after.get("customer").asText());
    assertEquals(doc.get("lines"), after.get("lines"));
    assertEquals("2.85", after.get("total").asText());
  }

  @Test
  void changingOnlyLinesStillBumpsTheDocumentsVersion() throws Exception {
    JsonNode doc = order("O-4");
    long id = doc.get("id").asLong();

    JsonNode after = ok(200, send("PATCH", PATH + "/" + id,
        "{\"version\": 0, \"payments\": [{\"amount\": \"2.00\"}]}", clerk));

    assertEquals(1, after.get("version").asInt());
    assertEquals("2.00", after.get("payments").get(0).get("amount").asText());
  }

  // ---- problems ----

  @Test
  void everyProblemIsReportedOnItsPath() throws Exception {
    assertEquals(List.of("lines[0].lineAmount:READ_ONLY", "lines[0].orderId:READ_ONLY", "lines[0].quantity:TOO_SMALL",
        "lines[0].unitPrice:INVALID_VALUE", "lines[1].quantity:REQUIRED", "orderNo:REQUIRED", "total:READ_ONLY"),
        errors(send("POST", PATH, """
            {"total": "9.99",
             "lines": [{"quantity": "-1", "unitPrice": "abc", "lineAmount": "1", "orderId": 5},
                       {"unitPrice": "1"}]}""", clerk)));
  }

  @Test
  void theDocumentsRulesAreCheckedToo() throws Exception {
    assertEquals(List.of("lines:TOO_FEW_LINES"),
        errors(send("POST", PATH, "{\"orderNo\": \"O-5\", \"lines\": []}", clerk)));
    assertEquals(List.of("payments:PAYMENTS_EXCEED_TOTAL"), errors(send("POST", PATH, """
        {"orderNo": "O-5", "lines": [{"quantity": "1", "unitPrice": "1"}], "payments": [{"amount": "5"}]}""",
        clerk)));
    assertEquals(0, count("SELECT COUNT(*) FROM test_orders WHERE order_no = 'O-5'"));
  }

  @Test
  void aLineOfAnotherDocumentIsUnknown() throws Exception {
    long other = order("O-6").get("lines").get(0).get("id").asLong();
    long id = order("O-7").get("id").asLong();

    assertEquals(List.of("lines[0].id:UNKNOWN_LINE"), errors(send("PATCH", PATH + "/" + id,
        "{\"version\": 0, \"lines\": [{\"id\": %d, \"quantity\": \"1\", \"unitPrice\": \"1\"}]}"
            .formatted(other), clerk)));
  }

  @Test
  void aStaleVersionShowsTheWholeDocumentAsItIsNow() throws Exception {
    long id = order("O-8").get("id").asLong();
    ok(200, send("PATCH", PATH + "/" + id, "{\"version\": 0, \"customer\": \"First\"}", clerk));

    HttpResponse<String> stale = send("PATCH", PATH + "/" + id, "{\"version\": 0, \"customer\": \"Second\"}", clerk);

    assertEquals(409, stale.statusCode(), stale.body());
    JsonNode current = MySqlTestStack.json(stale).get("current");
    assertEquals("First", current.get("customer").asText());
    assertEquals(2, current.get("lines").size());
  }

  // ---- calculate ----

  @Test
  void calculateRecomputesAndReportsWithoutSaving() throws Exception {
    JsonNode r = ok(200, send("POST", PATH + "/calculate",
        "{\"lines\": [{\"quantity\": \"200\", \"unitPrice\": \"1.5\"}]}", clerk));

    assertEquals("300.00", r.get("document").get("total").asText());
    assertEquals("300.00", r.get("document").get("lines").get(0).get("lineAmount").asText());
    JsonNode warning = r.get("warnings").get(0);
    assertEquals("lines[0].quantity", warning.get("field").asText());
    assertEquals("LARGE_QUANTITY", warning.get("code").asText());
    // what a save would say: no order number
    assertEquals("orderNo", r.get("errors").get(0).get("field").asText());
    assertEquals(0, count("SELECT COUNT(*) FROM test_order_lines WHERE quantity = 200"));
  }

  @Test
  void calculateOnAStoredDocumentMayChangeNothing() throws Exception {
    long id = order("O-9").get("id").asLong();

    JsonNode r = ok(200, send("POST", PATH + "/" + id + "/calculate", "", clerk));
    JsonNode changed = ok(200, send("POST", PATH + "/" + id + "/calculate",
        "{\"lines\": [{\"quantity\": \"1\", \"unitPrice\": \"1\"}]}", clerk));

    assertEquals("2.85", r.get("document").get("total").asText());
    assertEquals("[]", r.get("errors").toString());
    assertEquals("1.00", changed.get("document").get("total").asText());
    assertEquals(2, ok(200, send("GET", PATH + "/" + id, null, clerk)).get("lines").size());
  }

  // ---- rules, history, permissions ----

  @Test
  void aPostedDocumentIsLocked() throws Exception {
    long id = order("O-10").get("id").asLong();
    ok(200, send("PATCH", PATH + "/" + id, "{\"version\": 0, \"status\": \"POSTED\"}", clerk));

    HttpResponse<String> change = send("PATCH", PATH + "/" + id, "{\"version\": 1, \"customer\": \"x\"}", clerk);
    JsonNode meta = ok(200, send("GET", PATH + "/" + id, null, clerk)).get("_meta");

    assertEquals(409, change.statusCode(), change.body());
    assertEquals("RECORD_LOCKED", MySqlTestStack.json(change).get("code").asText());
    assertEquals("[\"*\"]", meta.get("locked").toString());
    assertFalse(meta.get("can").get("update").asBoolean());
    assertEquals(409, send("DELETE", PATH + "/" + id + "?version=1", null, clerk).statusCode());
  }

  @Test
  void eachSaveIsOneHistoryEntryWithItsLineChanges() throws Exception {
    JsonNode doc = order("O-11");
    long id = doc.get("id").asLong();
    long bolt = doc.get("lines").get(0).get("id").asLong();
    long nut = doc.get("lines").get(1).get("id").asLong();
    ok(200, send("PATCH", PATH + "/" + id,
        "{\"version\": 0, \"lines\": [{\"id\": %d, \"quantity\": \"4\"}]}".formatted(bolt), clerk));

    JsonNode history = ok(200, send("GET", PATH + "/" + id + "/history", null, clerk)).get("data");

    assertEquals(2, history.size());
    JsonNode update = history.get(0);
    assertEquals("UPDATED", update.get("action").asText());
    JsonNode lines = update.get("changes").get("lines").get("new");
    assertEquals("4", lines.get("changed").get(String.valueOf(bolt)).get("quantity").get("new").asText());
    assertEquals(nut, lines.get("removed").get(0).asLong());
    assertEquals("5.00", update.get("changes").get("total").get("new").asText());
    JsonNode created = history.get(1).get("changes");
    assertEquals(2, created.get("lines").get("new").get("added").size());
  }

  @Test
  void readersReadAndCanNotWrite() throws Exception {
    long id = order("O-12").get("id").asLong();

    assertEquals(2, ok(200, send("GET", PATH + "/" + id, null, reader)).get("lines").size());
    assertEquals(403, send("PATCH", PATH + "/" + id, "{\"version\": 0, \"customer\": \"x\"}", reader).statusCode());
    assertEquals(403, send("POST", PATH + "/calculate", "{}", reader).statusCode());
    assertEquals(403, send("GET", PATH + "/new", null, reader).statusCode());
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditRecord;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;

/**
 * The audit trail of generic writes, end to end: products created, changed
 * and deleted over HTTP, and what audit_log holds afterwards. Also that an
 * entry exists if and only if its write committed.
 */
class AuditTrailMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final String PRODUCTS = "/masterdata/products";

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static AuditLog audit;
  private static String editor; // U1, every product permission in ACME
  private static String reader; // U2, product read only (no audit.history.read)
  private static String otherAuditor; // U3, every product permission and audit.history.read, in OTHER

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(AuditTrailMySqlTest.class, AuthSettings.defaults(), AuditTrailMySqlTest::seed);
    uow = new UnitOfWorkFactory(stack.dataSource());
    audit = stack.services().get(AuditLog.class);
    for (String u : List.of("U1", "U2", "U3")) {
      stack.users().setPassword(u, PASSWORD.toCharArray());
    }
    editor = stack.login("U1", PASSWORD);
    reader = stack.login("U2", PASSWORD);
    otherAuditor = stack.login("U3", PASSWORD);
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
    st.execute("INSERT INTO currencies (id, code, name, is_enabled) VALUES (1, 'CAD', 'Canadian dollar', TRUE)");
    st.execute("INSERT INTO units_of_measure (id, code, description, is_enabled) VALUES (1, 'UN', 'Unit', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Jane Editor', 1, 1), (2, 1, 'U2', 'Reader', 1, 1), (3, 2, 'U3', 'Other auditor', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'EDITOR', 'Editor'), (11, 'READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'masterdata.product.read'), (10, 'masterdata.product.create'),
          (10, 'masterdata.product.update'), (10, 'masterdata.product.delete'), (10, 'audit.history.read'),
          (11, 'masterdata.product.read')""");
    st.execute("""
        INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 10), (2, 1, 11), (3, 2, 10)""");
    st.execute("""
        INSERT INTO product_categories (id, company_id, code, description, created_by, updated_by)
        VALUES (1, 1, 'HW', 'Hardware', 1, 1)""");
    st.execute("""
        INSERT INTO products (id, company_id, product_no, name, unit_of_measure_id, unit_amount, currency_id,
                              product_category_id, created_by, updated_by)
        VALUES (1, 1, 'P-001', 'Hex Bolt M8', 1, 0.2500, 1, 1, 1, 1)""");
  }

  // ---- helpers ----

  private static HttpResponse<String> send(String method, String path, Map<String, Object> body, String token)
      throws Exception {
    return stack.send(method, path,
        body == null ? null : JsonMapperFactory.newObjectMapper().writeValueAsString(body), token);
  }

  private static long create(String productNo) throws Exception {
    Map<String, Object> body = new HashMap<>(Map.of("productNo", productNo, "name", "Washer", "unitOfMeasureId", 1,
        "unitAmount", "0.0500", "currencyId", 1, "productCategoryId", 1));
    HttpResponse<String> r = send("POST", PRODUCTS, body, editor);
    assertEquals(201, r.statusCode(), r.body());
    return MySqlTestStack.json(r).get("id").asLong();
  }

  /** Newest first. */
  private static List<AuditRecord> history(long productId) {
    return uow.executeReadOnly(u -> audit.history(u, "product", productId, 1L, 100, 0));
  }

  private static List<String> actions(long productId) {
    return history(productId).stream().map(AuditRecord::action).toList();
  }

  // ---- what is recorded ----

  @Test
  void createUpdateAndDeleteAreRecordedWithWhoWhenAndValues() throws Exception {
    long id = create("P-AUD");

    AuditRecord created = history(id).getFirst();
    assertEquals("CREATED", created.action());
    assertEquals(1L, created.companyId());
    assertEquals(1L, created.userId());
    assertFalse(created.requestId() == null || created.requestId().isBlank());
    JsonNode productNo = created.changes().get("productNo");
    assertTrue(productNo.get("old").isNull());
    assertEquals("P-AUD", productNo.get("new").asText());
    assertEquals("0.0500", created.changes().get("unitAmount").get("new").asText()); // decimals as strings

    // Only what really changed: unitAmount is resent with another scale.
    assertEquals(200, send("PATCH", PRODUCTS + "/" + id,
        Map.of("version", 0, "name", "Steel washer", "unitAmount", "0.05"), editor).statusCode());
    AuditRecord updated = history(id).getFirst();
    assertEquals("UPDATED", updated.action());
    assertEquals(List.of("name"), iterable(updated.changes().fieldNames()));
    assertEquals("Washer", updated.changes().get("name").get("old").asText());
    assertEquals("Steel washer", updated.changes().get("name").get("new").asText());
    assertTrue(!updated.occurredAt().isBefore(created.occurredAt()));

    // A PATCH that changes nothing leaves no entry.
    assertEquals(200, send("PATCH", PRODUCTS + "/" + id, Map.of("version", 1, "name", "Steel washer"), editor)
        .statusCode());
    assertEquals(List.of("UPDATED", "CREATED"), actions(id));

    assertEquals(204, send("DELETE", PRODUCTS + "/" + id + "?version=2", null, editor).statusCode());
    AuditRecord deleted = history(id).getFirst();
    assertEquals("DELETED", deleted.action());
    assertEquals(0, deleted.changes().size());
    assertEquals(List.of("DELETED", "UPDATED", "CREATED"), actions(id));
  }

  // ---- an entry if and only if the write committed ----

  @Test
  void rejectedWritesLeaveNoEntry() throws Exception {
    assertEquals(409, send("PATCH", PRODUCTS + "/1", Map.of("version", 7, "name", "Stale"), editor).statusCode());
    assertEquals(422, send("PATCH", PRODUCTS + "/1", Map.of("version", 0, "unitAmount", "abc"), editor).statusCode());
    assertEquals(403, send("PATCH", PRODUCTS + "/1", Map.of("version", 0, "name", "Nope"), reader).statusCode());
    assertEquals(List.of(), history(1));
  }

  @Test
  void aWriteFailsIfItsEntryCannotBeWritten() throws Exception {
    long id = create("P-NOAUDIT");
    uow.run(u -> Jdbc.update(u, "RENAME TABLE audit_log TO audit_log_off"));
    try {
      HttpResponse<String> r = send("PATCH", PRODUCTS + "/" + id, Map.of("version", 0, "name", "Unaudited"), editor);
      assertEquals(500, r.statusCode(), r.body());
    } finally {
      uow.run(u -> Jdbc.update(u, "RENAME TABLE audit_log_off TO audit_log"));
    }
    JsonNode row = MySqlTestStack.json(send("GET", PRODUCTS + "/" + id, null, editor));
    assertEquals("Washer", row.get("name").asText()); // the change rolled back with its entry
    assertEquals(0, row.get("version").asLong());
    assertEquals(List.of("CREATED"), actions(id));
  }

  // ---- the history API ----

  private static JsonNode historyOf(long id, String query, String token) throws Exception {
    HttpResponse<String> r = send("GET", PRODUCTS + "/" + id + "/history" + query, null, token);
    assertEquals(200, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  @Test
  void theHistoryShowsWhoChangedWhatNewestFirst() throws Exception {
    long id = create("P-HIST");
    assertEquals(200, send("PATCH", PRODUCTS + "/" + id, Map.of("version", 0, "name", "Big washer"), editor)
        .statusCode());

    JsonNode body = historyOf(id, "", editor);
    JsonNode items = body.get("data");
    assertEquals(2, items.size());
    JsonNode latest = items.get(0);
    assertEquals("UPDATED", latest.get("action").asText());
    assertEquals("Washer", latest.get("changes").get("name").get("old").asText());
    assertEquals("Big washer", latest.get("changes").get("name").get("new").asText());
    assertEquals(1, latest.get("user").get("id").asLong());
    assertEquals("U1", latest.get("user").get("userNo").asText());
    assertEquals("Jane Editor", latest.get("user").get("name").asText());
    Instant.parse(latest.get("occurredAt").asText());
    assertEquals("CREATED", items.get(1).get("action").asText());
    assertEquals(50, body.get("page").get("limit").asInt());

    // Paging.
    JsonNode second = historyOf(id, "?limit=1&offset=1", editor).get("data");
    assertEquals(1, second.size());
    assertEquals("CREATED", second.get(0).get("action").asText());
    assertEquals(400, send("GET", PRODUCTS + "/" + id + "/history?limit=0", null, editor).statusCode());
  }

  @Test
  void theHistoryNeedsTheReadPermissionAndAuditRead() throws Exception {
    long id = create("P-PERM");
    assertEquals(403, send("GET", PRODUCTS + "/" + id + "/history", null, reader).statusCode()); // no audit.history.read
    assertEquals(401, send("GET", PRODUCTS + "/" + id + "/history", null, null).statusCode());
  }

  @Test
  void anotherCompanySeesNoneOfIt() throws Exception {
    long id = create("P-MINE");
    assertEquals(0, historyOf(id, "", otherAuditor).get("data").size());
  }

  @Test
  void aDeletedRecordKeepsItsHistory() throws Exception {
    long id = create("P-GONE");
    assertEquals(204, send("DELETE", PRODUCTS + "/" + id + "?version=0", null, editor).statusCode());
    assertEquals(404, send("GET", PRODUCTS + "/" + id, null, editor).statusCode());
    JsonNode items = historyOf(id, "", editor).get("data");
    assertEquals(List.of("DELETED", "CREATED"), List.of(items.get(0).get("action").asText(),
        items.get(1).get("action").asText()));
  }

  private static List<String> iterable(Iterator<String> it) {
    List<String> out = new ArrayList<>();
    it.forEachRemaining(out::add);
    return out;
  }
}

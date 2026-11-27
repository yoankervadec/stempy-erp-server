package com.lesconstructionssapete.stempyerpserver.modules.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;

/**
 * GET / PUT / DELETE /auth/me/preferences (docs/ARCHITECTURE.md §27) against
 * the real schema: values kept as sent, per user, not per company, and the
 * key, value and count limits.
 */
class PreferenceRoutesMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final String BASE = "/auth/me/preferences";

  private static MySqlTestStack stack;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(PreferenceRoutesMySqlTest.class, AuthSettings.defaults(),
        PreferenceRoutesMySqlTest::seed);
    for (String userNo : new String[] { "U1", "U2", "U3", "U4", "U5" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE), (2, 'BETA', TRUE)");
    // U1: two companies and no default, so company-scoped routes need X-Company-Id.
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, NULL, 'U1', 'Two companies', 1, 1),
          (2, 1,    'U2', 'Other user',    1, 1),
          (3, 1,    'U3', 'Validation',    1, 1),
          (4, 1,    'U4', 'Many keys',     1, 1),
          (5, 1,    'U5', 'Deleter',       1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (1, 2), (2, 1), (3, 1), (4, 1), (5, 1)");
  }

  private static String token(String userNo) throws Exception {
    return stack.login(userNo, PASSWORD);
  }

  private static HttpResponse<String> get(String token) throws Exception {
    return stack.send("GET", BASE, null, token);
  }

  private static HttpResponse<String> put(String token, String key, String body) throws Exception {
    return stack.send("PUT", BASE + "/" + key, body, token);
  }

  private static HttpResponse<String> delete(String token, String key) throws Exception {
    return stack.send("DELETE", BASE + "/" + key, null, token);
  }

  private static JsonNode ok(HttpResponse<String> r) throws Exception {
    assertEquals(200, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static JsonNode items(String token) throws Exception {
    return ok(get(token)).get("items");
  }

  private static List<String> keys(String token) throws Exception {
    List<String> out = new ArrayList<>();
    items(token).forEach(i -> out.add(i.get("key").asText()));
    return out;
  }

  /** The one field error of a 422, as "field:code". */
  private static String fieldError(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    JsonNode errors = MySqlTestStack.json(r).get("errors");
    assertEquals(1, errors.size(), r.body());
    return errors.get(0).get("field").asText() + ":" + errors.get(0).get("code").asText();
  }

  @Test
  void aUserWithNoneGetsAnEmptyList() throws Exception {
    HttpResponse<String> r = get(token("U2"));
    assertEquals("{\"items\":[]}", r.body());
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  void valuesComeBackAsSent() throws Exception {
    String token = token("U1");
    String object = "{\"pageSize\":50,\"columns\":[{\"key\":\"name\",\"width\":1.25},{\"key\":\"code\",\"hidden\":true}],"
        + "\"zeta\":\"été\",\"alpha\":null}";

    JsonNode written = ok(put(token, "list.product", "{\"value\": " + object + "}"));
    assertEquals("list.product", written.get("key").asText());
    assertEquals(object, written.get("value").toString());
    Instant updatedAt = Instant.parse(written.get("updatedAt").asText());

    ok(put(token, "shell.menuPinned", "{\"value\": false}"));
    ok(put(token, "views.product@2", "{\"value\": [1, \"a\", 12345678901234567890.125]}"));

    JsonNode items = items(token);
    assertEquals(3, items.size());
    assertEquals("list.product", items.get(0).get("key").asText()); // by key
    assertEquals(object, items.get(0).get("value").toString()); // key order, numbers and accents kept
    assertEquals(updatedAt, Instant.parse(items.get(0).get("updatedAt").asText()));
    assertEquals("false", items.get(1).get("value").toString());
    assertEquals("[1,\"a\",12345678901234567890.125]", items.get(2).get("value").toString());
  }

  @Test
  void aPutReplacesAndTheLastWriteWins() throws Exception {
    String token = token("U5");
    ok(put(token, "menu", "{\"value\": {\"hidden\": [\"gl\"]}}"));
    ok(put(token, "menu", "{\"value\": \"plain\"}"));
    assertEquals(1, items(token).size());
    assertEquals("\"plain\"", items(token).get(0).get("value").toString());
  }

  @Test
  void deleteRemovesAndAMissingKeyIsFine() throws Exception {
    String token = token("U5");
    ok(put(token, "list.filterPaneOpen", "{\"value\": true}"));
    assertEquals(204, delete(token, "list.filterPaneOpen").statusCode());
    assertFalse(keys(token).contains("list.filterPaneOpen"));
    assertEquals(204, delete(token, "list.filterPaneOpen").statusCode());
    assertEquals(204, delete(token, "never.set").statusCode());
  }

  @Test
  void eachUserSeesOnlyTheirOwn() throws Exception {
    String other = token("U2");
    ok(put(token("U3"), "only.mine", "{\"value\": 1}"));
    assertFalse(keys(other).contains("only.mine"));
    assertEquals(204, delete(other, "only.mine").statusCode()); // removes nothing of U3's
    assertTrue(keys(token("U3")).contains("only.mine"));
  }

  @Test
  void theCompanyIsNeitherNeededNorChecked() throws Exception {
    String token = token("U1"); // two companies, no default
    assertEquals(400, stack.send("GET", "/auth/me", null, token).statusCode()); // COMPANY_REQUIRED
    assertEquals(200, stack.send("PUT", BASE + "/any.company", "{\"value\": 1}", token, 99L).statusCode());
    assertEquals(200, stack.send("GET", BASE, null, token, 2L).statusCode());
    assertEquals(204, stack.send("DELETE", BASE + "/any.company", null, token, 99L).statusCode());
  }

  @Test
  void keysAreCheckedAndCaseSensitive() throws Exception {
    String token = token("U3");
    assertEquals("key:INVALID_FORMAT", fieldError(put(token, "has%20space", "{\"value\": 1}")));
    assertEquals("key:INVALID_FORMAT", fieldError(put(token, "x".repeat(101), "{\"value\": 1}")));
    assertEquals("key:INVALID_FORMAT", fieldError(delete(token, "bad%2Fslash")));
    ok(put(token, "Case.Key:a@b-c_d", "{\"value\": 1}"));
    ok(put(token, "case.key:a@b-c_d", "{\"value\": 2}"));
    assertTrue(keys(token).containsAll(List.of("Case.Key:a@b-c_d", "case.key:a@b-c_d")));
    ok(put(token, "x".repeat(100), "{\"value\": 1}"));
  }

  @Test
  void theValueIsRequiredNotNullAndAtMost64Kb() throws Exception {
    String token = token("U3");
    assertEquals("value:REQUIRED", fieldError(put(token, "v", "{}")));
    assertEquals("value:REQUIRED", fieldError(put(token, "v", "{\"value\": null}")));
    assertEquals("other:UNKNOWN_FIELD", fieldError(put(token, "v", "{\"value\": 1, \"other\": 2}")));
    assertEquals(422, put(token, "v", "[1]").statusCode());
    assertEquals(400, put(token, "v", "not json").statusCode());

    // 65536 bytes exactly: two quotes and 65534 characters.
    ok(put(token, "big", "{\"value\": \"" + "a".repeat(65_534) + "\"}"));
    assertEquals("value:VALUE_TOO_LARGE", fieldError(put(token, "big", "{\"value\": \"" + "a".repeat(65_535) + "\"}")));
    // Bytes, not characters: an accented 'e' is two in UTF-8 (32,768 of them and the quotes: 65,538).
    assertEquals("value:VALUE_TOO_LARGE",
        fieldError(put(token, "big", "{\"value\": \"" + "é".repeat(32_768) + "\"}")));
  }

  @Test
  void atMost500KeysButAnExistingOneCanStillChange() throws Exception {
    String token = token("U4");
    try (Connection c = stack.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO user_preferences (user_id, pref_key, value_json, updated_at) VALUES (4, ?, 'true', NOW())")) {
      for (int i = 0; i < 499; i++) {
        ps.setString(1, "k" + i);
        ps.addBatch();
      }
      ps.executeBatch();
      c.commit();
    }
    ok(put(token, "k500", "{\"value\": 1}")); // the 500th
    HttpResponse<String> refused = put(token, "k501", "{\"value\": 1}");
    assertEquals(409, refused.statusCode(), refused.body());
    assertEquals("TOO_MANY_PREFERENCES", MySqlTestStack.json(refused).get("code").asText());
    ok(put(token, "k0", "{\"value\": false}"));
    assertEquals(204, delete(token, "k0").statusCode());
    ok(put(token, "k501", "{\"value\": 1}"));
    assertEquals(500, items(token).size());
  }

  @Test
  void signingInIsRequired() throws Exception {
    assertEquals(401, get(null).statusCode());
    assertEquals(401, put(null, "x", "{\"value\": 1}").statusCode());
    assertEquals(401, delete(null, "x").statusCode());
  }
}

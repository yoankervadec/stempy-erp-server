package com.lesconstructionssapete.stempyerpserver.modules.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;

/**
 * The request's language (docs/ARCHITECTURE.md §23.5) against the real schema:
 * the user's own choice, else the company's default, in login, /auth/me,
 * /meta and translated errors; PATCH /auth/me to choose.
 */
class LanguageRoutesMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final long ACME = 1; // French
  private static final long ENCO = 2; // English

  private static MySqlTestStack stack;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(LanguageRoutesMySqlTest.class, AuthSettings.defaults(),
        LanguageRoutesMySqlTest::seed);
    for (String userNo : new String[] { "U1", "U2", "U3", "U4" }) {
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
    st.execute("INSERT INTO companies (id, code, is_enabled, default_language) VALUES"
        + " (1, 'ACME', TRUE, 'fr'), (2, 'ENCO', TRUE, 'en')");
    // U1: both companies, ACME by default. U2: ENCO only. U3: chose English. U4: changes theirs.
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, language, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Both',     NULL, 1, 1),
          (2, 2, 'U2', 'English',  NULL, 1, 1),
          (3, 1, 'U3', 'Own',      'en', 1, 1),
          (4, 1, 'U4', 'Changer',  NULL, 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (1, 2), (2, 2), (3, 1), (4, 1), (4, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'READER', 'Reader')");
    st.execute("INSERT INTO role_permissions (role_id, permission) VALUES (10, 'masterdata.product.read')");
    st.execute("""
        INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES
          (1, 1, 10), (1, 2, 10), (2, 2, 10), (3, 1, 10), (4, 1, 10), (4, 2, 10)""");
  }

  private static JsonNode ok(HttpResponse<String> r) throws Exception {
    assertEquals(200, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static JsonNode login(String userNo) throws Exception {
    return ok(stack.send("POST", "/auth/login", MySqlTestStack.loginBody(userNo, PASSWORD), null));
  }

  private static String token(String userNo) throws Exception {
    return login(userNo).get("token").asText();
  }

  private static HttpResponse<String> patchMe(String token, String body, long company) throws Exception {
    return stack.send("PATCH", "/auth/me", body, token, company);
  }

  private static String meLanguage(String token, long company) throws Exception {
    return ok(stack.send("GET", "/auth/me", null, token, company)).get("language").asText();
  }

  @Test
  void loginAnswersInTheDefaultCompanysLanguageUnlessTheUserChose() throws Exception {
    assertEquals("fr", login("U1").get("language").asText());
    assertEquals("en", login("U2").get("language").asText());
    JsonNode own = login("U3");
    assertEquals("en", own.get("language").asText());
    assertEquals("en", own.get("user").get("language").asText());
    assertTrue(login("U1").get("user").get("language").isNull());
  }

  @Test
  void eachRequestFollowsItsCompany() throws Exception {
    String token = token("U1");

    assertEquals("fr", meLanguage(token, ACME));
    assertEquals("en", meLanguage(token, ENCO));
    assertEquals("Produits", productResource(token, ACME).get("label").asText());
    assertEquals("Products", productResource(token, ENCO).get("label").asText());
  }

  private static JsonNode productResource(String token, long company) throws Exception {
    JsonNode meta = ok(stack.send("GET", "/meta", null, token, company));
    for (JsonNode r : meta.get("resources")) {
      if (r.get("key").asText().equals("product")) {
        return r;
      }
    }
    throw new AssertionError("no product resource in " + meta);
  }

  @Test
  void aUsersOwnLanguageWinsInEveryCompanyAndCanBeCleared() throws Exception {
    String token = token("U4");

    JsonNode chosen = ok(patchMe(token, "{\"language\": \"en\"}", ACME));
    assertEquals("en", chosen.get("language").asText());
    assertEquals("en", chosen.get("user").get("language").asText());
    // the cache was cleared: the next request already sees it
    assertEquals("en", meLanguage(token, ACME));
    assertEquals("Products", productResource(token, ACME).get("label").asText());

    JsonNode cleared = ok(patchMe(token, "{\"language\": null}", ACME));
    assertEquals("fr", cleared.get("language").asText());
    assertEquals("en", meLanguage(token, ENCO));
    assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE entity = 'user' AND entity_id = 4"
        + " AND action = 'UPDATED'"));
  }

  @Test
  void theLanguageIsValidatedAndTheErrorsAreTranslated() throws Exception {
    String token = token("U1");

    JsonNode bad = MySqlTestStack.json(patchMe(token, "{\"language\": \"de\", \"name\": \"x\"}", ACME));
    JsonNode missing = MySqlTestStack.json(patchMe(token, "{}", ENCO));

    assertEquals("VALIDATION_FAILED", bad.get("code").asText());
    assertEquals("Données invalides", bad.get("title").asText());
    for (JsonNode e : bad.get("errors")) {
      if (e.get("field").asText().equals("language")) {
        assertEquals("INVALID_VALUE", e.get("code").asText());
        assertEquals("n'est pas une valeur permise", e.get("message").asText());
      } else {
        assertEquals("name", e.get("field").asText());
        assertEquals("UNKNOWN_FIELD", e.get("code").asText());
      }
    }
    // in ENCO, the same user reads English: the code's own message
    assertEquals("REQUIRED", missing.get("errors").get(0).get("code").asText());
    assertTrue(missing.get("errors").get(0).get("message").asText().startsWith("is required"));
  }

  @Test
  void aFailedLoginIsInTheDefaultLanguage() throws Exception {
    JsonNode r = MySqlTestStack.json(stack.send("POST", "/auth/login", MySqlTestStack.loginBody("U1", "wrong!"),
        null));

    assertEquals("INVALID_CREDENTIALS", r.get("code").asText());
    assertEquals("Numéro d'utilisateur ou mot de passe invalide", r.get("detail").asText());
  }

  private static long count(String sql) throws SQLException {
    try (Connection c = stack.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }
}

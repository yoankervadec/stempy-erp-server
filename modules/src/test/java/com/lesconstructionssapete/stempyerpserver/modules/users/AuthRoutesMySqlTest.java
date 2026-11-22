package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.AuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionTokens;

/**
 * Login, sessions and logout against the real MySQL schema. See
 * MySqlTestStack for the database setup; skipped when it is unavailable.
 *
 * Each test uses its own user where it changes state (disable, lockout,
 * password change), so tests don't depend on their order.
 */
class AuthRoutesMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final AuthSettings SETTINGS = AuthSettings.defaults(); // 8h idle, 7d max, 5 failures / 15 min

  private static MySqlTestStack stack;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(AuthRoutesMySqlTest.class, SETTINGS, AuthRoutesMySqlTest::seed);
    for (String userNo : new String[] { "U1", "U2", "U3", "U4", "U5", "U6", "U7" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
    sql("UPDATE user_credentials SET is_enabled = FALSE WHERE user_id = 6");
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, description, is_enabled) VALUES"
        + " (1, 'ACME', 'Acme Inc.', TRUE), (2, 'CLOSED', 'Closed Ltd.', FALSE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, is_enabled, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin',               TRUE,  1, 1),
          (2, 1, 'U2', 'Disabled user',       FALSE, 1, 1),
          (3, 1, 'U3', 'Disabled later',      TRUE,  1, 1),
          (4, 1, 'U4', 'Lockout target',      TRUE,  1, 1),
          (5, 2, 'U5', 'Closed company user', TRUE,  1, 1),
          (6, 1, 'U6', 'Credential disabled', TRUE,  1, 1),
          (7, 1, 'U7', 'Password changer',    TRUE,  1, 1)""");
    st.execute("""
        INSERT INTO user_companies (user_id, company_id) VALUES
          (1, 1), (2, 1), (3, 1), (4, 1), (5, 2), (6, 1), (7, 1)""");
    // U1 may read products in ACME
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'READER', 'Reader')");
    st.execute("INSERT INTO role_permissions (role_id, permission) VALUES (10, 'masterdata.product.read')");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 10)");
  }

  // ---- helpers ----

  private static HttpResponse<String> login(String userNo, String password) throws Exception {
    return stack.send("POST", "/auth/login", MySqlTestStack.loginBody(userNo, password), null);
  }

  private static HttpResponse<String> me(String token) throws Exception {
    return stack.send("GET", "/auth/me", null, token);
  }

  private static String code(HttpResponse<String> r) throws Exception {
    return MySqlTestStack.json(r).get("code").asText();
  }

  private static void assertRejected(int status, String code, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    assertEquals(code, code(r));
  }

  /**
   * Runs one statement; Instant parameters are bound as UTC, like the server
   * does.
   */
  private static int sql(String sql, Object... params) throws SQLException {
    try (Connection c = stack.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        Object p = params[i];
        ps.setObject(i + 1, p instanceof Instant t ? OffsetDateTime.ofInstant(t, ZoneOffset.UTC) : p);
      }
      int n = ps.executeUpdate();
      c.commit();
      return n;
    }
  }

  private static Instant lastSeen(String token) throws SQLException {
    try (Connection c = stack.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT last_seen_at FROM user_sessions WHERE token_hash = ?")) {
      ps.setBytes(1, SessionTokens.hash(token));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, OffsetDateTime.class).toInstant();
      }
    }
  }

  // ---- login ----

  @Test
  void loginReturnsTokenProfileAndLifetime() throws Exception {
    Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    HttpResponse<String> r = login("U1", PASSWORD);

    assertEquals(200, r.statusCode(), r.body());
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());
    JsonNode body = MySqlTestStack.json(r);
    assertEquals(43, body.get("token").asText().length());
    assertEquals(SETTINGS.idleTimeout().toSeconds(), body.get("idleTimeoutSeconds").asLong());
    Instant expiresAt = Instant.parse(body.get("expiresAt").asText());
    assertTrue(!expiresAt.isBefore(before.plus(SETTINGS.maxLifetime())), "expiresAt " + expiresAt);
    JsonNode user = body.get("user");
    assertEquals(1, user.get("id").asLong());
    assertEquals("U1", user.get("userNo").asText());
    assertEquals("Admin", user.get("name").asText());
    assertEquals(1, body.get("companies").size());
    assertEquals("ACME", body.get("companies").get(0).get("code").asText());
    assertEquals(1, body.get("defaultCompanyId").asLong());
  }

  @Test
  void onlyTheTokenHashIsStored() throws Exception {
    String token = stack.login("U1", PASSWORD);

    try (Connection c = stack.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT token_hash, client_ip FROM user_sessions WHERE token_hash = ?")) {
      ps.setBytes(1, SessionTokens.hash(token));
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "session row stored under the token's hash");
        assertArrayEquals(SessionTokens.hash(token), rs.getBytes("token_hash"));
        assertEquals("127.0.0.1", rs.getString("client_ip"));
      }
    }
  }

  @Test
  void userNoIsCaseInsensitive() throws Exception {
    assertEquals(200, login("u1", PASSWORD).statusCode());
  }

  @Test
  void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
    HttpResponse<String> wrong = login("U1", "not the password");
    HttpResponse<String> unknown = login("NOBODY", PASSWORD);

    assertRejected(401, "INVALID_CREDENTIALS", wrong);
    assertRejected(401, "INVALID_CREDENTIALS", unknown);
    assertEquals(MySqlTestStack.json(wrong).get("detail"), MySqlTestStack.json(unknown).get("detail"));
  }

  @Test
  void disabledAccountsCannotLogInEvenWithTheRightPassword() throws Exception {
    assertRejected(401, "INVALID_CREDENTIALS", login("U2", PASSWORD)); // user disabled
    assertRejected(401, "INVALID_CREDENTIALS", login("U5", PASSWORD)); // their only company is disabled
    assertRejected(401, "INVALID_CREDENTIALS", login("U6", PASSWORD)); // credential disabled
  }

  @Test
  void repeatedFailuresLockTheUserOutForThisClient() throws Exception {
    for (int i = 0; i < SETTINGS.maxFailedLogins(); i++) {
      assertRejected(401, "INVALID_CREDENTIALS", login("U4", "wrong " + i));
    }
    // locked: even the right password is refused until the window passes
    assertRejected(429, "LOGIN_RATE_LIMITED", login("U4", PASSWORD));
  }

  @Test
  void loginBodyIsValidated() throws Exception {
    HttpResponse<String> missing = stack.send("POST", "/auth/login", "{}", null);
    assertEquals(422, missing.statusCode(), missing.body());
    assertEquals(2, MySqlTestStack.json(missing).get("errors").size());

    assertEquals(400, stack.send("POST", "/auth/login", "{not json", null).statusCode());
    assertEquals(400, stack.send("POST", "/auth/login",
        "{\"userNo\":\"U1\",\"password\":\"x\",\"companyId\":1}", null).statusCode()); // unknown property
  }

  // ---- using the session ----

  @Test
  void meReturnsTheLoggedInUser() throws Exception {
    HttpResponse<String> r = me(stack.login("U1", PASSWORD));

    assertEquals(200, r.statusCode(), r.body());
    JsonNode body = MySqlTestStack.json(r);
    assertEquals("Admin", body.get("user").get("name").asText());
    assertEquals("ACME", body.get("company").get("code").asText());
    assertEquals("masterdata.product.read", body.get("permissions").get(0).asText());
    assertEquals(1, body.get("permissions").size());
  }

  @Test
  void protectedRoutesNeedAValidBearerToken() throws Exception {
    assertRejected(401, "UNAUTHENTICATED", stack.send("QUERY", "/masterdata/products", "", null));
    assertRejected(401, "SESSION_INVALID", stack.send("QUERY", "/masterdata/products", "", "not-a-real-token"));

    HttpResponse<String> basic = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(stack.uri("/auth/me")).header(AuthFilter.HEADER, "Basic dTE6cHc=").build(),
        HttpResponse.BodyHandlers.ofString());
    assertRejected(401, "AUTH_HEADER_INVALID", basic);

    String token = stack.login("U1", PASSWORD);
    assertEquals(200, stack.send("QUERY", "/masterdata/products", "", token).statusCode());
  }

  @Test
  void logoutEndsTheSession() throws Exception {
    String token = stack.login("U1", PASSWORD);

    assertEquals(204, stack.send("POST", "/auth/logout", null, token).statusCode());
    assertRejected(401, "SESSION_INVALID", me(token));
    assertRejected(401, "SESSION_INVALID", stack.send("POST", "/auth/logout", null, token));
  }

  @Test
  void sessionsAreIndependent() throws Exception {
    String desktop = stack.login("U1", PASSWORD);
    String laptop = stack.login("U1", PASSWORD);

    stack.send("POST", "/auth/logout", null, desktop);

    assertEquals(200, me(laptop).statusCode());
  }

  // ---- expiry ----

  @Test
  void idleSessionExpires() throws Exception {
    String token = stack.login("U1", PASSWORD);
    Instant longAgo = Instant.now().minus(SETTINGS.idleTimeout()).minus(Duration.ofMinutes(1));
    sql("UPDATE user_sessions SET last_seen_at = ? WHERE token_hash = ?", longAgo, SessionTokens.hash(token));

    assertRejected(401, "SESSION_INVALID", me(token));
  }

  @Test
  void activityKeepsTheSessionAlive() throws Exception {
    String token = stack.login("U1", PASSWORD);
    Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
    sql("UPDATE user_sessions SET last_seen_at = ? WHERE token_hash = ?", twoHoursAgo, SessionTokens.hash(token));

    assertEquals(200, me(token).statusCode());
    assertTrue(lastSeen(token).isAfter(twoHoursAgo.plus(Duration.ofHours(1))), "last_seen_at refreshed");
  }

  @Test
  void sessionEndsAtItsMaximumLifetimeEvenIfUsed() throws Exception {
    String token = stack.login("U1", PASSWORD);
    sql("UPDATE user_sessions SET expires_at = ? WHERE token_hash = ?",
        Instant.now().minus(Duration.ofMinutes(1)), SessionTokens.hash(token));

    assertRejected(401, "SESSION_INVALID", me(token));
  }

  // ---- account changes take effect immediately ----

  @Test
  void disablingAUserEndsTheirSessionsAtOnce() throws Exception {
    String token = stack.login("U3", PASSWORD);
    assertEquals(200, me(token).statusCode());

    sql("UPDATE users SET is_enabled = FALSE WHERE id = 3");

    assertRejected(401, "SESSION_INVALID", me(token));
  }

  @Test
  void changingThePasswordEndsSessionsAndReplacesTheOldPassword() throws Exception {
    String token = stack.login("U7", PASSWORD);

    stack.users().setPassword("U7", "a brand new password".toCharArray());

    assertRejected(401, "SESSION_INVALID", me(token));
    assertRejected(401, "INVALID_CREDENTIALS", login("U7", PASSWORD));
    assertEquals(200, login("U7", "a brand new password").statusCode());
  }

  // ---- UsersApi.setPassword ----

  @Test
  void setPasswordRejectsShortPasswordsAndUnknownUsers() {
    assertThrows(ValidationFailedException.class, () -> stack.users().setPassword("U1", "short".toCharArray()));
    assertThrows(ResourceNotFoundException.class,
        () -> stack.users().setPassword("NOBODY", "long enough password".toCharArray()));
  }
}

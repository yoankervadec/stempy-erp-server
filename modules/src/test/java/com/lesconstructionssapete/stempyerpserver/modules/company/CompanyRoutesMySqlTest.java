package com.lesconstructionssapete.stempyerpserver.modules.company;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/**
 * The company module (docs/ARCHITECTURE.md §25) against the real schema: the
 * company's own settings, exchange rates and their rules, and CompanyApi.
 *
 * Tests that change rows use their own currencies or dates, so they don't
 * depend on order.
 */
class CompanyRoutesMySqlTest {

  private static final String PASSWORD = "test-password";
  private static final String COMPANIES = "/company/companies";
  private static final String RATES = "/company/exchange-rates";
  private static final long CAD = 1, USD = 2, EUR = 3, GBP = 4, JPY = 5, CHF = 6;

  private static MySqlTestStack stack;
  private static String editor; // U1: every company permission in ACME (company 1)
  private static String reader; // U2: read only, ACME
  private static String other; // U3: every company permission in OTHER (company 2)

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(CompanyRoutesMySqlTest.class, AuthSettings.defaults(), CompanyRoutesMySqlTest::seed);
    for (String u : List.of("U1", "U2", "U3")) {
      stack.users().setPassword(u, PASSWORD.toCharArray());
    }
    editor = stack.login("U1", PASSWORD);
    reader = stack.login("U2", PASSWORD);
    other = stack.login("U3", PASSWORD);
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("""
        INSERT INTO companies (id, code, description, is_enabled, base_currency_code, default_language) VALUES
          (1, 'ACME', 'Acme inc.', TRUE, 'CAD', 'fr'), (2, 'OTHER', NULL, TRUE, 'CAD', 'fr')""");
    st.execute("""
        INSERT INTO currencies (id, code, name, is_enabled) VALUES
          (1, 'CAD', 'Canadian dollar', TRUE), (2, 'USD', 'US dollar', TRUE), (3, 'EUR', 'Euro', TRUE),
          (4, 'GBP', 'Pound', TRUE), (5, 'JPY', 'Yen', TRUE), (6, 'CHF', 'Franc', TRUE)""");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Editor', 1, 1), (2, 1, 'U2', 'Reader', 1, 1), (3, 2, 'U3', 'Other editor', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'EDITOR', 'Editor'), (11, 'READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'company.settings.read'), (10, 'company.settings.update'),
          (10, 'company.exchangeRate.read'), (10, 'company.exchangeRate.create'),
          (10, 'company.exchangeRate.update'), (10, 'company.exchangeRate.delete'),
          (10, 'masterdata.product.read'),
          (11, 'company.settings.read'), (11, 'company.exchangeRate.read')""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 10), (2, 1, 11), (3, 2, 10)");
  }

  // ---- helpers ----

  private static HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
    return stack.send(method, path, body == null ? null : JsonMapperFactory.newObjectMapper().writeValueAsString(body),
        token);
  }

  private static JsonNode ok(int status, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    return MySqlTestStack.json(r);
  }

  private static JsonNode rate(String token, long currencyId, String date, String rate) throws Exception {
    return ok(201, send("POST", RATES, Map.of("currencyId", currencyId, "startingDate", date, "rate", rate), token));
  }

  private static Map<String, String> fieldErrors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    Map<String, String> out = new HashMap<>();
    MySqlTestStack.json(r).get("errors").forEach(e -> out.put(e.get("field").asText(), e.get("code").asText()));
    return out;
  }

  private static List<Long> ids(JsonNode page) {
    List<Long> out = new ArrayList<>();
    page.get("data").forEach(row -> out.add(row.get("id").asLong()));
    return out;
  }

  // ---- the company's settings ----

  @Test
  void eachCallerSeesOnlyTheirOwnCompany() throws Exception {
    assertEquals(List.of(1L), ids(ok(200, stack.send("QUERY", COMPANIES, "{}", reader))));
    assertEquals(List.of(2L), ids(ok(200, stack.send("QUERY", COMPANIES, "{}", other))));

    JsonNode acme = ok(200, send("GET", COMPANIES + "/1", null, reader));
    assertEquals("ACME", acme.get("code").asText());
    assertEquals("CAD", acme.get("baseCurrencyCode").asText());
    assertEquals("fr", acme.get("defaultLanguage").asText());
    assertEquals(404, send("GET", COMPANIES + "/2", null, reader).statusCode());
    assertEquals(404, send("PATCH", COMPANIES + "/2", Map.of("version", 0, "description", "x"), editor).statusCode());
  }

  @Test
  void onlyTheDescriptionAndTheLanguageChange() throws Exception {
    assertEquals(Map.of("code", "READ_ONLY", "baseCurrencyCode", "READ_ONLY", "isEnabled", "READ_ONLY"),
        fieldErrors(send("PATCH", COMPANIES + "/1",
            Map.of("version", 0, "code", "NEW", "baseCurrencyCode", "USD", "isEnabled", false), editor)));
    assertEquals(Map.of("defaultLanguage", "INVALID_FORMAT"),
        fieldErrors(send("PATCH", COMPANIES + "/1", Map.of("version", 0, "defaultLanguage", "de"), editor)));
    assertEquals(403, send("PATCH", COMPANIES + "/1", Map.of("version", 0, "description", "x"), reader).statusCode());
  }

  @Test
  void aNewDefaultLanguageIsUsedAtOnce() throws Exception {
    assertEquals("fr", ok(200, send("GET", "/auth/me", null, other)).get("language").asText());

    JsonNode row = ok(200, send("PATCH", COMPANIES + "/2",
        Map.of("version", 0, "description", "Other inc.", "defaultLanguage", "en"), other));
    assertEquals("Other inc.", row.get("description").asText());
    assertEquals(1, row.get("version").asLong());
    assertEquals(3, row.get("updatedBy").asLong());

    assertEquals("en", ok(200, send("GET", "/auth/me", null, other)).get("language").asText()); // cache emptied
  }

  // ---- exchange rates ----

  @Test
  void aRateIsTheCompanysOwn() throws Exception {
    JsonNode row = rate(editor, USD, "2026-01-01", "1.3650");
    assertEquals("1.3650000000", row.get("rate").asText());
    assertEquals("2026-01-01", row.get("startingDate").asText());

    assertTrue(ids(ok(200, stack.send("QUERY", RATES, "{}", reader))).contains(row.get("id").asLong()));
    assertEquals(List.of(), ids(ok(200, stack.send("QUERY", RATES, "{}", other))));
    rate(other, USD, "2026-01-01", "1.4000"); // the same date in another company

    // the rate of a date: through the currency's code, latest starting on or before it
    JsonNode found = ok(200, stack.send("QUERY", RATES, """
        {"where": {"and": [{"field": "currency.code", "op": "equals", "value": "USD"},
                           {"field": "startingDate", "op": "lessThanOrEquals", "value": "2026-01-15"}]},
         "sort": [{"field": "startingDate", "dir": "desc"}], "page": {"limit": 1, "offset": 0}}""", reader));
    assertEquals(List.of(row.get("id").asLong()), ids(found));
  }

  @Test
  void oneRatePerCurrencyAndDateAndNeverTheBaseCurrency() throws Exception {
    rate(editor, EUR, "2026-02-01", "1.5");
    assertEquals(Map.of("startingDate", "RATE_EXISTS"), fieldErrors(send("POST", RATES,
        Map.of("currencyId", EUR, "startingDate", "2026-02-01", "rate", "1.6"), editor)));
    assertEquals(Map.of("currencyId", "BASE_CURRENCY"), fieldErrors(send("POST", RATES,
        Map.of("currencyId", CAD, "startingDate", "2026-02-01", "rate", "1"), editor)));
    assertEquals(Map.of("currencyId", "INVALID_REFERENCE"), fieldErrors(send("POST", RATES,
        Map.of("currencyId", 999, "startingDate", "2026-02-01", "rate", "1"), editor)));
  }

  @Test
  void aRateIsAboveZeroWithTenDecimalsAtMost() throws Exception {
    assertEquals(Map.of("rate", "TOO_SMALL"), fieldErrors(send("POST", RATES,
        Map.of("currencyId", CHF, "startingDate", "2026-03-01", "rate", "0"), editor)));
    assertEquals(Map.of("rate", "TOO_MANY_DECIMALS"), fieldErrors(send("POST", RATES,
        Map.of("currencyId", CHF, "startingDate", "2026-03-01", "rate", "1.00000000001"), editor)));
    assertEquals(Map.of("currencyId", "REQUIRED", "startingDate", "REQUIRED", "rate", "REQUIRED"),
        fieldErrors(send("POST", RATES, Map.of(), editor)));
  }

  @Test
  void aRateMovesToAFreeDateOnlyAndKeepsItsCurrency() throws Exception {
    rate(editor, GBP, "2026-04-01", "1.70");
    JsonNode second = rate(editor, GBP, "2026-05-01", "1.72");
    String path = RATES + "/" + second.get("id").asLong();

    assertEquals(Map.of("startingDate", "RATE_EXISTS"),
        fieldErrors(send("PATCH", path, Map.of("version", 0, "startingDate", "2026-04-01"), editor)));
    JsonNode changed = ok(200, send("PATCH", path, Map.of("version", 0, "rate", "1.73"), editor)); // same date: itself
    assertEquals("1.7300000000", changed.get("rate").asText());
    assertEquals(422, send("PATCH", path, Map.of("version", 1, "currencyId", EUR), editor).statusCode());
  }

  @Test
  void aDeletedRateCanBeEnteredAgain() throws Exception {
    JsonNode wrong = rate(editor, JPY, "2026-06-01", "0.9");
    assertEquals(204, send("DELETE", RATES + "/" + wrong.get("id").asLong() + "?version=0", null, editor).statusCode());
    rate(editor, JPY, "2026-06-01", "0.0091");
  }

  @Test
  void readersCanOnlyRead() throws Exception {
    assertEquals(403, send("POST", RATES, Map.of("currencyId", USD, "startingDate", "2030-01-01", "rate", "1"),
        reader).statusCode());
  }

  // ---- CompanyApi ----

  @Test
  void theApiUsesTheLatestRateStartingOnOrBeforeTheDate() throws Exception {
    // company 2's own USD rates, so other tests' company 1 rates don't matter
    rate(other, USD, "2026-07-01", "1.30");
    rate(other, USD, "2026-08-01", "1.40");
    CompanyApi api = stack.services().get(CompanyApi.class);

    stack.uowFactory().executeReadOnly(uow -> {
      java.util.Currency usd = Money.currency("USD");
      assertEquals(Money.currency("CAD"), api.baseCurrency(uow, 2));
      assertEquals(0, new BigDecimal("1.30").compareTo(api.exchangeRate(uow, 2, usd, LocalDate.of(2026, 7, 31))));
      assertEquals(0, new BigDecimal("1.40").compareTo(api.exchangeRate(uow, 2, usd, LocalDate.of(2026, 8, 1))));
      assertEquals(BigDecimal.ONE, api.exchangeRate(uow, 2, Money.currency("CAD"), LocalDate.of(2000, 1, 1)));
      assertEquals(Money.of("140.00", "CAD"),
          api.toBaseCurrency(uow, 2, Money.of("100.00", "USD"), LocalDate.of(2026, 9, 1)).rounded());
      assertEquals(Money.of("5.00", "CAD"), api.toBaseCurrency(uow, 2, Money.of("5.00", "CAD"), LocalDate.of(2026, 9, 1)));

      ConflictException none = assertThrows(ConflictException.class,
          () -> api.exchangeRate(uow, 2, Money.currency("EUR"), LocalDate.of(2026, 9, 1)));
      assertEquals("EXCHANGE_RATE_NOT_FOUND", none.code());
      return null;
    });
  }
}

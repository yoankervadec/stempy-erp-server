package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersModule;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseMigrator;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.web.DevAuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityQueryEndpoint;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * End-to-end check of the items routes against a real MySQL: Flyway
 * migrations, HikariCP, the generated SQL, JDBC type mapping and JSON output,
 * wired the same way Main wires them.
 *
 * Runs against the local MySQL server, using the connection settings from the
 * repository's .env (DATABASE_HOST, DATABASE_PORT, DATABASE_USER,
 * DATABASE_PASSWORD) and the URL params from db.properties, like Main.
 *
 * It uses its OWN database, TEST_DATABASE_NAME (default stempy_erp_test),
 * never DATABASE_NAME: every run drops all its tables (Flyway clean), migrates
 * from scratch and seeds. As a guard, the name must end in "_test". One-time
 * setup, as a MySQL admin:
 *
 * <pre>
 * CREATE DATABASE stempy_erp_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
 * GRANT ALL PRIVILEGES ON stempy_erp_test.* TO '&lt;DATABASE_USER&gt;'@'localhost';
 * </pre>
 *
 * Skipped (not failed) when the test database can't be reached, so the build
 * still passes on machines without it. One seeded data set is shared by all
 * tests, so tests only read.
 *
 * Seed data (company 1 is the default caller):
 *
 * <pre>
 * id  company  productNo  name                  unitAmount  category
 *  1  1        P-001      Hex Bolt M8           0.2500      HW
 *  2  1        P-002      Wood screw 50% off    0.1000      HW
 *  3  1        P-003      Wire 14 AWG           1.7500      EL
 *  4  1        P-004      Old bolt              1.0000      HW   (soft-deleted)
 *  5  2        P-B01      Hex Bolt M8           0.3000      HW-B
 * </pre>
 */
class ItemRoutesMySqlTest {

  private static final String DEFAULT_TEST_DATABASE = "stempy_erp_test";

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static HikariDataSource dataSource;
  private static WebServer server;

  @BeforeAll
  static void startStack() throws SQLException {
    var db = testDatabaseConfig();
    assumeReachable(db);

    // Start from an empty schema every run, then apply the real migrations.
    Flyway.configure()
        .dataSource(db.jdbcUrl(), db.user(), db.password())
        .cleanDisabled(false)
        .load()
        .clean();
    DatabaseMigrator.migrate(db.jdbcUrl(), db.user(), db.password());
    dataSource = DataSources.create(db);
    seed();

    // Same lifecycle as Main, minus the parts these routes don't use.
    List<ERPModule> modules = List.of(new UsersModule(), new ItemService());
    var entities = new EntityRegistry();
    modules.forEach(m -> m.registerEntities(entities));
    entities.freeze();

    var services = new ServiceRegistry();
    services.register(EntityQueryEndpoint.class,
        new EntityQueryEndpoint(new UnitOfWorkFactory(dataSource), QueryLimits.defaults()));
    modules.forEach(m -> m.registerServices(services));
    services.freeze();

    server = new WebServer(new WebServerConfig("127.0.0.1", 0),
        new ExceptionMapper(new ErrorTranslator()), new DevAuthFilter()::handle);
    server.start(routes -> modules.forEach(m -> m.registerRoutes(routes, services)));
  }

  @AfterAll
  static void stopStack() {
    if (server != null) {
      server.stop();
    }
    if (dataSource != null) {
      dataSource.close();
    }
  }

  /** Same sources as Main: .env for the connection, db.properties for URL params. */
  private static DatabaseConfig testDatabaseConfig() {
    // Surefire runs in modules/, the .env lives at the repository root.
    // Real environment variables take precedence over the file.
    Dotenv env = Dotenv.configure().directory("..").ignoreIfMissing().load();

    String name = env.get("TEST_DATABASE_NAME", DEFAULT_TEST_DATABASE);
    if (!name.endsWith("_test") || name.equals(env.get("DATABASE_NAME"))) {
      throw new IllegalStateException("Refusing to wipe database '" + name
          + "': the test database name must end in _test and differ from DATABASE_NAME");
    }
    String host = env.get("DATABASE_HOST");
    String port = env.get("DATABASE_PORT");
    String user = env.get("DATABASE_USER");
    String password = env.get("DATABASE_PASSWORD");
    if (host == null || port == null || user == null || password == null) {
      skip("DATABASE_HOST/PORT/USER/PASSWORD not set (.env or environment)");
    }

    return DatabaseConfig.of(host, Integer.parseInt(port), name, urlParams(), user, password);
  }

  private static Map<String, String> urlParams() {
    String prefix = "database.url.params.";
    Properties props = new Properties();
    try (InputStream in = ItemRoutesMySqlTest.class.getClassLoader().getResourceAsStream("db.properties")) {
      if (in == null) {
        throw new IllegalStateException("db.properties not found on the classpath");
      }
      props.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load db.properties", e);
    }
    return props.stringPropertyNames().stream()
        .filter(k -> k.startsWith(prefix))
        .collect(Collectors.toMap(k -> k.substring(prefix.length()), props::getProperty));
  }

  private static void assumeReachable(DatabaseConfig db) {
    try (Connection c = DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password())) {
      // reachable
    } catch (SQLException e) {
      // Only the message: it never contains the password.
      skip("test database not reachable (" + db + "): " + e.getMessage()
          + ". See the class Javadoc for the one-time setup.");
    }
  }

  /**
   * Surefire does not report why a @BeforeAll assumption skipped a class (it
   * shows "Tests run: 0"), so say it on the console too.
   */
  private static void skip(String reason) {
    System.err.println("[ItemRoutesMySqlTest] SKIPPED: " + reason);
    assumeTrue(false, reason);
  }

  private static void seed() throws SQLException {
    try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
      // users.created_by references users itself; the first user can't satisfy
      // that any other way. Session-scoped, and restored before the connection
      // goes back to the pool.
      st.execute("SET FOREIGN_KEY_CHECKS = 0");
      try {
        st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE), (2, 'OTHER', TRUE)");
        st.execute("INSERT INTO currencies (id, code, is_enabled) VALUES (1, 'CAD', TRUE)");
        st.execute("INSERT INTO units_of_measure (id, code, is_enabled) VALUES (1, 'UN', TRUE)");
        st.execute("""
            INSERT INTO users (id, company_id, user_no, name, created_by, updated_by) VALUES
              (1, 1, 'U1', 'Admin', 1, 1),
              (2, 2, 'U2', 'Other admin', 2, 2)""");
        st.execute("""
            INSERT INTO product_categories (id, company_id, code, description, created_by, updated_by) VALUES
              (1, 1, 'HW', 'Hardware', 1, 1),
              (2, 1, 'EL', 'Electrical', 1, 1),
              (3, 2, 'HW-B', 'Other hardware', 2, 2)""");
        st.execute("""
            INSERT INTO products (id, company_id, product_no, name, unit_of_measure_id, unit_amount,
                                  currency_id, product_category_id, created_by, updated_by, deleted_at) VALUES
              (1, 1, 'P-001', 'Hex Bolt M8',        1, 0.2500, 1, 1, 1, 1, NULL),
              (2, 1, 'P-002', 'Wood screw 50% off', 1, 0.1000, 1, 1, 1, 1, NULL),
              (3, 1, 'P-003', 'Wire 14 AWG',        1, 1.7500, 1, 2, 1, 1, NULL),
              (4, 1, 'P-004', 'Old bolt',           1, 1.0000, 1, 1, 1, 1, CURRENT_TIMESTAMP),
              (5, 2, 'P-B01', 'Hex Bolt M8',        1, 0.3000, 1, 3, 2, 2, NULL)""");
        c.commit();
      } finally {
        st.execute("SET FOREIGN_KEY_CHECKS = 1");
      }
    }
  }

  // ---- helpers ----

  private static HttpResponse<String> query(String path, String body, long companyId)
      throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .method("QUERY", HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .header(DevAuthFilter.USER_HEADER, String.valueOf(companyId)) // user id == company id in the seed
        .header(DevAuthFilter.COMPANY_HEADER, String.valueOf(companyId))
        .build();
    return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode ok(HttpResponse<String> r) throws IOException {
    assertEquals(200, r.statusCode(), r.body());
    return JSON.readTree(r.body());
  }

  /** Product ids returned for company 1. */
  private static List<Long> productIds(String body) throws Exception {
    return ids(ok(query("/masterdata/products", body, 1)));
  }

  private static List<Long> ids(JsonNode page) {
    List<Long> out = new ArrayList<>();
    page.get("data").forEach(row -> out.add(row.get("id").asLong()));
    return out;
  }

  private static String where(String field, String op, String jsonValue) {
    return "{\"where\":{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + jsonValue + "}}";
  }

  // ---- tenancy and soft delete ----

  @Test
  void emptyQueryListsOnlyTheCallersLiveProductsInIdOrder() throws Exception {
    assertEquals(List.of(1L, 2L, 3L), productIds(""));
  }

  @Test
  void anotherCompanySeesOnlyItsOwnProducts() throws Exception {
    assertEquals(List.of(5L), ids(ok(query("/masterdata/products", "", 2))));
  }

  // ---- JDBC type mapping and JSON shape ----

  @Test
  void rowIsMappedWithMySqlTypes() throws Exception {
    JsonNode row = ok(query("/masterdata/products", where("productNo", "equals", "\"P-001\""), 1))
        .get("data").get(0);

    assertEquals("Hex Bolt M8", row.get("name").asText());
    assertTrue(row.get("unitAmount").isTextual(), "decimals must be JSON strings");
    assertEquals("0.2500", row.get("unitAmount").asText()); // DECIMAL(19,4) scale kept
    assertTrue(row.get("isEnabled").asBoolean());
    assertEquals(0, row.get("version").asLong());
    assertEquals(1, row.get("productCategoryId").asLong());
    Instant.parse(row.get("createdAt").asText()); // TIMESTAMP -> ISO-8601 instant
    assertTrue(row.get("deletedAt").isNull());
    assertFalse(row.has("companyId"), "internal fields are never returned");
  }

  // ---- operators: these are where SQL dialect bugs show up ----

  @Test
  void containsIsCaseInsensitive() throws Exception {
    assertEquals(List.of(1L), productIds(where("name", "contains", "\"bolt\"")));
  }

  @Test
  void likeWildcardsInTheValueAreLiteral() throws Exception {
    assertEquals(List.of(2L), productIds(where("name", "contains", "\"0%\"")));
    // unescaped, '_' would match any character, i.e. every product
    assertEquals(List.of(), productIds(where("name", "contains", "\"_\"")));
  }

  @Test
  void startsWith() throws Exception {
    assertEquals(List.of(3L), productIds(where("name", "startsWith", "\"wi\"")));
  }

  @Test
  void notEquals() throws Exception {
    assertEquals(List.of(2L, 3L), productIds(where("productNo", "notEquals", "\"P-001\"")));
  }

  @Test
  void inList() throws Exception {
    assertEquals(List.of(1L, 3L), productIds(where("productNo", "in", "[\"P-001\", \"P-003\", \"P-B01\"]")));
  }

  @Test
  void decimalComparisonAcceptsStringValues() throws Exception {
    assertEquals(List.of(1L, 3L), productIds(where("unitAmount", "greaterThan", "\"0.2\"")));
  }

  @Test
  void booleanFilter() throws Exception {
    assertEquals(List.of(1L, 2L, 3L), productIds(where("isEnabled", "equals", "true")));
  }

  @Test
  void andOrNotCombine() throws Exception {
    String body = """
        {"where": {"and": [
          {"or": [
            {"field": "name", "op": "contains", "value": "bolt"},
            {"field": "name", "op": "contains", "value": "wire"}
          ]},
          {"not": {"field": "productNo", "op": "equals", "value": "P-003"}}
        ]}}""";
    assertEquals(List.of(1L), productIds(body));
  }

  // ---- relations (LEFT JOINs, company-scoped) ----

  @Test
  void filterThroughCategoryRelation() throws Exception {
    assertEquals(List.of(1L, 2L), productIds(where("category.code", "equals", "\"HW\"")));
  }

  @Test
  void sortThroughCategoryRelationThenById() throws Exception {
    String body = "{\"sort\":[{\"field\":\"category.code\",\"dir\":\"asc\"}]}";
    assertEquals(List.of(3L, 1L, 2L), productIds(body)); // EL before HW; ties by id
  }

  @Test
  void filterThroughCreatedByUserRelation() throws Exception {
    assertEquals(List.of(1L, 2L, 3L), productIds(where("createdByUser.name", "equals", "\"Admin\"")));
  }

  // ---- sorting and paging ----

  @Test
  void sortDescending() throws Exception {
    assertEquals(List.of(3L, 1L, 2L), productIds("{\"sort\":[{\"field\":\"unitAmount\",\"dir\":\"desc\"}]}"));
  }

  @Test
  void paging() throws Exception {
    JsonNode page = ok(query("/masterdata/products", "{\"page\":{\"limit\":1,\"offset\":1}}", 1));
    assertEquals(List.of(2L), ids(page));
    assertEquals(1, page.get("page").get("limit").asInt());
    assertEquals(1, page.get("page").get("offset").asInt());
  }

  // ---- errors and the second route ----

  @Test
  void nonFilterableFieldIs422() throws Exception {
    HttpResponse<String> r = query("/masterdata/products", where("currencyId", "equals", "1"), 1);
    assertEquals(422, r.statusCode(), r.body());
    assertEquals("NOT_FILTERABLE", JSON.readTree(r.body()).get("errors").get(0).get("code").asText());
  }

  @Test
  void productCategoriesRoute() throws Exception {
    JsonNode page = ok(query("/masterdata/product-categories", "{\"sort\":[{\"field\":\"code\"}]}", 1));
    List<String> codes = new ArrayList<>();
    page.get("data").forEach(row -> codes.add(row.get("code").asText()));
    assertEquals(List.of("EL", "HW"), codes);
  }
}

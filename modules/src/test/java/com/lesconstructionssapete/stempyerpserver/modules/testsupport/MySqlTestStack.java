package com.lesconstructionssapete.stempyerpserver.modules.testsupport;

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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.ItemService;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersModule;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseMigrator;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.security.AuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityQueryEndpoint;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * The whole server against the local MySQL, wired like Main: fresh schema
 * (Flyway clean + migrate), test-specific seed data, every module, the real
 * AuthFilter, a real HTTP port.
 *
 * Connection settings come from the repository's .env (DATABASE_HOST, _PORT,
 * _USER, _PASSWORD) and URL params from db.properties, like Main. The database
 * is TEST_DATABASE_NAME (default stempy_erp_test), never DATABASE_NAME: every
 * start WIPES it. As a guard, the name must end in "_test". One-time setup, as
 * a MySQL admin:
 *
 * <pre>
 * CREATE DATABASE stempy_erp_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
 * GRANT ALL PRIVILEGES ON stempy_erp_test.* TO '&lt;DATABASE_USER&gt;'@'localhost';
 * </pre>
 *
 * When the database can't be reached, start() skips the calling test class
 * (and says why on the console) instead of failing the build.
 *
 * Test classes run one after another (Surefire's default), each wiping and
 * re-seeding the same database, so they never see each other's data.
 */
public final class MySqlTestStack implements AutoCloseable {

  /** Fast hashing so seeding and logins don't cost 0.3s each. Production uses the default. */
  public static final PasswordHasher FAST_HASHER = new PasswordHasher(1_000);

  private static final String DEFAULT_TEST_DATABASE = "stempy_erp_test";
  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /** Inserts the test's rows. Runs with foreign key checks off (users reference themselves). */
  @FunctionalInterface
  public interface Seeder {
    void seed(Statement st) throws SQLException;
  }

  private final HikariDataSource dataSource;
  private final WebServer server;
  private final UsersApi users;

  private MySqlTestStack(HikariDataSource dataSource, WebServer server, UsersApi users) {
    this.dataSource = dataSource;
    this.server = server;
    this.users = users;
  }

  public static MySqlTestStack start(Class<?> testClass, AuthSettings auth, Seeder seeder) throws SQLException {
    DatabaseConfig db = config(testClass);
    assumeReachable(testClass, db);

    Flyway.configure()
        .dataSource(db.jdbcUrl(), db.user(), db.password())
        .cleanDisabled(false)
        .load()
        .clean();
    DatabaseMigrator.migrate(db.jdbcUrl(), db.user(), db.password());
    HikariDataSource dataSource = DataSources.create(db);
    try {
      seed(dataSource, seeder);

      var uowFactory = new UnitOfWorkFactory(dataSource);
      List<ERPModule> modules = List.of(
          new UsersModule(auth, FAST_HASHER, Clock.systemUTC()),
          new ItemService());

      var entities = new EntityRegistry();
      modules.forEach(m -> m.registerEntities(entities));
      entities.freeze();

      var services = new ServiceRegistry();
      services.register(UnitOfWorkFactory.class, uowFactory);
      services.register(EntityQueryEndpoint.class, new EntityQueryEndpoint(uowFactory, QueryLimits.defaults()));
      modules.forEach(m -> m.registerServices(services));
      services.freeze();

      var authFilter = new AuthFilter(services.get(SessionAuthenticator.class));
      var server = new WebServer(new WebServerConfig("127.0.0.1", 0),
          new ExceptionMapper(new ErrorTranslator()), authFilter::handle);
      server.start(routes -> modules.forEach(m -> m.registerRoutes(routes, services)));
      return new MySqlTestStack(dataSource, server, services.get(UsersApi.class));
    } catch (RuntimeException | SQLException e) {
      dataSource.close();
      throw e;
    }
  }

  public UsersApi users() {
    return users;
  }

  public HikariDataSource dataSource() {
    return dataSource;
  }

  /** Logs in and returns the session token; fails the test if login doesn't succeed. */
  public String login(String userNo, String password) throws IOException, InterruptedException {
    HttpResponse<String> r = send("POST", "/auth/login", loginBody(userNo, password), null);
    if (r.statusCode() != 200) {
      throw new AssertionError("login as " + userNo + " failed: " + r.statusCode() + " " + r.body());
    }
    return json(r).get("token").asText();
  }

  public URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  public static String loginBody(String userNo, String password) {
    return "{\"userNo\":\"" + userNo + "\",\"password\":\"" + password + "\"}";
  }

  /** Sends a request; {@code body} and {@code token} may be null. */
  public HttpResponse<String> send(String method, String path, String body, String token)
      throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    if (token != null) {
      b.header(AuthFilter.HEADER, "Bearer " + token);
    }
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  public static JsonNode json(HttpResponse<String> r) throws IOException {
    return JSON.readTree(r.body());
  }

  @Override
  public void close() {
    server.stop();
    dataSource.close();
  }

  // ---- setup ----

  private static void seed(HikariDataSource dataSource, Seeder seeder) throws SQLException {
    try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
      st.execute("SET FOREIGN_KEY_CHECKS = 0");
      try {
        seeder.seed(st);
        c.commit();
      } finally {
        st.execute("SET FOREIGN_KEY_CHECKS = 1"); // session-scoped: restore before returning it to the pool
      }
    }
  }

  private static DatabaseConfig config(Class<?> testClass) {
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
      skip(testClass, "DATABASE_HOST/PORT/USER/PASSWORD not set (.env or environment)");
    }
    return DatabaseConfig.of(host, Integer.parseInt(port), name, urlParams(), user, password);
  }

  private static Map<String, String> urlParams() {
    String prefix = "database.url.params.";
    Properties props = new Properties();
    try (InputStream in = MySqlTestStack.class.getClassLoader().getResourceAsStream("db.properties")) {
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

  private static void assumeReachable(Class<?> testClass, DatabaseConfig db) {
    try (Connection c = DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password())) {
      // reachable
    } catch (SQLException e) {
      // Only the message: it never contains the password.
      skip(testClass, "test database not reachable (" + db + "): " + e.getMessage()
          + ". See MySqlTestStack's Javadoc for the one-time setup.");
    }
  }

  /**
   * Surefire does not report why a @BeforeAll assumption skipped a class (it
   * shows "Tests run: 0"), so say it on the console too.
   */
  private static void skip(Class<?> testClass, String reason) {
    System.err.println("[" + testClass.getSimpleName() + "] SKIPPED: " + reason);
    assumeTrue(false, reason);
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.modules.AllModules;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseMigrator;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ModuleHost;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.AuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * The whole server against the local MySQL, started like Main (AllModules +
 * ModuleHost): fresh schema (Flyway clean + migrate), test-specific seed
 * data, the real AuthFilter, a real HTTP port.
 *
 * Connection settings come from the repository's .env (DATABASE_HOST, _PORT,
 * _USER, _PASSWORD) and pool / URL settings from db.properties, like Main. The database
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

  /**
   * Fast hashing so seeding and logins don't cost 0.3s each. Production uses the
   * default.
   */
  public static final PasswordHasher FAST_HASHER = new PasswordHasher(1_000);

  private static final String DEFAULT_TEST_DATABASE = "stempy_erp_test";
  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /**
   * Inserts the test's rows. Runs with foreign key checks off (users reference
   * themselves).
   */
  @FunctionalInterface
  public interface Seeder {
    void seed(Statement st) throws SQLException;
  }

  private final HikariDataSource dataSource;
  private final WebServer server;
  private final ServiceRegistry services;

  private MySqlTestStack(HikariDataSource dataSource, WebServer server, ServiceRegistry services) {
    this.dataSource = dataSource;
    this.server = server;
    this.services = services;
  }

  public static MySqlTestStack start(Class<?> testClass, AuthSettings auth, Seeder seeder) throws SQLException {
    return start(testClass, auth, seeder, List.of());
  }

  /** As start(...), with test-only modules (e.g. an event recorder) after the real ones. */
  public static MySqlTestStack start(Class<?> testClass, AuthSettings auth, Seeder seeder, List<ERPModule> extra)
      throws SQLException {
    DatabaseConfig db = config(testClass);
    assumeReachable(testClass, db);

    Flyway.configure()
        .dataSource(db.jdbcUrl(), db.user(), db.password())
        .cleanDisabled(false)
        .load()
        .clean();

    // The same startup as Main, apart from the hasher and the auth settings.
    List<ERPModule> modules = new ArrayList<>(AllModules.create(authSettings(auth), FAST_HASHER));
    modules.addAll(extra);
    var host = new ModuleHost(modules);
    DatabaseMigrator.migrate(db);
    HikariDataSource dataSource = DataSources.create(db);
    try {
      seed(dataSource, seeder);
      host.start(new UnitOfWorkFactory(dataSource));
      WebServer server = host.startWebServer(new WebServerConfig("127.0.0.1", 0));
      return new MySqlTestStack(dataSource, server, host.services());
    } catch (RuntimeException | SQLException e) {
      dataSource.close();
      throw e;
    }
  }

  public UsersApi users() {
    return services.get(UsersApi.class);
  }

  /** Everything the modules published, as the server would see it. */
  public ServiceRegistry services() {
    return services;
  }

  public HikariDataSource dataSource() {
    return dataSource;
  }

  /**
   * Logs in and returns the session token; fails the test if login doesn't
   * succeed.
   */
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
    return send(method, path, body, token, null);
  }

  /** As send(...), choosing the company with X-Company-Id when {@code companyId} isn't null. */
  public HttpResponse<String> send(String method, String path, String body, String token, Long companyId)
      throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    if (token != null) {
      b.header(AuthFilter.HEADER, "Bearer " + token);
    }
    if (companyId != null) {
      b.header(AuthFilter.COMPANY_HEADER, companyId.toString());
    }
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  public static JsonNode json(HttpResponse<String> r) throws IOException {
    return JSON.readTree(r.body());
  }

  @Override
  public void close() {
    try (dataSource) {
      server.stop();
    }
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
    Map<String, String> env = new HashMap<>();
    Dotenv.configure().directory("..").ignoreIfMissing().load().entries()
        .forEach(e -> env.put(e.getKey(), e.getValue()));
    Settings settings = Settings.fromClasspath("db.properties").overriddenBy(Settings.of(env));

    String name = settings.get("TEST_DATABASE_NAME", DEFAULT_TEST_DATABASE);
    if (!name.endsWith("_test") || name.equals(settings.get("DATABASE_NAME", null))) {
      throw new IllegalStateException("Refusing to wipe database '" + name
          + "': the test database name must end in _test and differ from DATABASE_NAME");
    }
    for (String key : List.of("DATABASE_HOST", "DATABASE_PORT", "DATABASE_USER", "DATABASE_PASSWORD")) {
      if (settings.find(key).isEmpty()) {
        skip(testClass, "DATABASE_HOST/PORT/USER/PASSWORD not set (.env or environment)");
      }
    }
    return DatabaseConfig.from(settings.overriddenBy(Settings.of(Map.of("DATABASE_NAME", name))));
  }

  /** The test's AuthSettings, as the auth.* keys AllModules reads. */
  private static Settings authSettings(AuthSettings a) {
    return Settings.of(Map.of(
        "auth.session.idleTimeout", a.idleTimeout().toString(),
        "auth.session.maxLifetime", a.maxLifetime().toString(),
        "auth.login.maxFailures", Integer.toString(a.maxFailedLogins()),
        "auth.login.lockout", a.lockout().toString(),
        "auth.permissions.cacheTtl", a.permissionCacheTtl().toString()));
  }

  private static void assumeReachable(Class<?> testClass, DatabaseConfig db) {
    try (Connection _ = DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password())) {
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

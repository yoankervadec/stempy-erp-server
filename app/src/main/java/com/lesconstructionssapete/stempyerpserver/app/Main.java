package com.lesconstructionssapete.stempyerpserver.app;

import java.io.Console;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationEnvironment;
import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationProperties;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseMigrator;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.ItemsModule;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthService;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersModule;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.AuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityQueryEndpoint;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthChecks;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthRoutes;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Usage:
 *
 * <pre>
 * java -jar stempy.jar                       start the server
 * java -jar stempy.jar set-password USER_NO  set a user's password (prompts, no echo)
 * </pre>
 */
public class Main {

  /**
   * Every business module, in dependency order: a module may only use what the
   * modules listed BEFORE it registered (entities, services).
   */
  private static List<ERPModule> modules() {
    return List.of(
        new UsersModule(authSettings()),
        new ItemsModule());
  }

  private static AuthSettings authSettings() {
    AuthSettings d = AuthSettings.defaults();
    return new AuthSettings(
        duration("auth.session.idleTimeout", d.idleTimeout()),
        duration("auth.session.maxLifetime", d.maxLifetime()),
        ApplicationProperties.getInt("auth.login.maxFailures", d.maxFailedLogins()),
        duration("auth.login.lockout", d.lockout()));
  }

  private static Duration duration(String key, Duration defaultValue) {
    String value = ApplicationProperties.get(key);
    return value == null ? defaultValue : Duration.parse(value);
  }

  public static void main(String[] args) {
    if (args.length > 0 && !(args.length == 2 && args[0].equals("set-password"))) {
      System.err.println("Usage: (no arguments) to start the server, or: set-password USER_NO");
      System.exit(2);
    }

    List<ERPModule> modules = modules();

    // Entity definitions first: a broken one fails here, before touching the DB.
    var entities = new EntityRegistry();
    modules.forEach(m -> m.registerEntities(entities));
    entities.freeze();

    var db = databaseConfig();

    DatabaseMigrator.migrate(
        db.jdbcUrl(),
        db.user(),
        db.password());

    HikariDataSource dataSource = DataSources.create(db);

    var uowFactory = new UnitOfWorkFactory(dataSource);

    var eventBus = new EventBus();

    var services = new ServiceRegistry();
    services.register(UnitOfWorkFactory.class, uowFactory);
    services.register(EventBus.class, eventBus);
    services.register(EntityRegistry.class, entities);
    services.register(EntityQueryEndpoint.class, new EntityQueryEndpoint(uowFactory, QueryLimits.defaults()));
    modules.forEach(m -> m.registerServices(services));
    services.freeze();

    modules.forEach(m -> m.registerEventHandlers(eventBus, services));
    eventBus.freeze();

    if (args.length == 2) {
      setPassword(services.get(UsersApi.class), args[1]);
      dataSource.close();
      return;
    }

    var authFilter = new AuthFilter(services.get(SessionAuthenticator.class));
    var exceptionMapper = new ExceptionMapper(new ErrorTranslator());
    var webServer = new WebServer(
        WebServerConfig.ofPort(ApplicationProperties.getInt("server.port")), exceptionMapper, authFilter::handle);

    var health = new HealthChecks().add("database", () -> uowFactory.executeReadOnly(uow -> {
      try (var st = uow.connection().createStatement(); var rs = st.executeQuery("SELECT 1")) {
        return rs.next();
      } catch (SQLException e) {
        throw new DataAccessException(e);
      }
    }));

    webServer.start(routes -> {
      HealthRoutes.register(routes, health);
      modules.forEach(m -> m.registerRoutes(routes, services));
    });
    // Stop taking requests first, then release the pool they were using.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        webServer.stop();
      } finally {
        dataSource.close();
      }
    }, "shutdown"));
  }

  /** Connection from .env, pool and driver settings from db.properties. */
  private static DatabaseConfig databaseConfig() {
    DatabaseConfig d = DatabaseConfig.of(
        ApplicationEnvironment.getRequired("DATABASE_HOST"),
        ApplicationEnvironment.getInt("DATABASE_PORT"),
        ApplicationEnvironment.getRequired("DATABASE_NAME"),
        ApplicationProperties.getByPrefix("database.url.params"),
        ApplicationEnvironment.getRequired("DATABASE_USER"),
        ApplicationEnvironment.getRequired("DATABASE_PASSWORD"));

    var driverProperties = ApplicationProperties.getByPrefix("database.mysql.performance.settings");

    return d
        .withPool(
            ApplicationProperties.get("database.pool.name", d.poolName()),
            ApplicationProperties.getInt("database.pool.maxSize", d.maxPoolSize()),
            ApplicationProperties.getInt("database.pool.minIdle", d.minIdle()),
            getLong("database.pool.connectionTimeoutMs", d.connectionTimeoutMs()),
            getLong("database.pool.leakDetectionThresholdMs", d.leakDetectionThresholdMs()))
        .withDriverProperties(driverProperties.isEmpty() ? d.driverProperties() : driverProperties);
  }

  private static long getLong(String key, long defaultValue) {
    String value = ApplicationProperties.get(key);
    return value == null ? defaultValue : Long.parseLong(value.trim());
  }

  /** Prompts twice without echo, so the password never lands in shell history or logs. */
  private static void setPassword(UsersApi users, String userNo) {
    Console console = System.console();
    if (console == null) {
      System.err.println("set-password needs an interactive terminal (no console attached)");
      System.exit(1);
    }
    console.printf("Passwords must be %d to %d characters.%n",
        AuthService.MIN_PASSWORD_LENGTH, AuthService.MAX_PASSWORD_LENGTH);
    char[] first = console.readPassword("New password for %s: ", userNo);
    char[] second = console.readPassword("Repeat it: ");
    try {
      if (first == null || second == null || !Arrays.equals(first, second)) {
        System.err.println("Passwords don't match; nothing changed.");
        System.exit(1);
      }
      users.setPassword(userNo, first); // wipes it
      System.out.println("Password set for " + userNo + "; their existing sessions were ended.");
    } catch (ValidationFailedException e) {
      // The message alone is just "Validation failed"; the reasons are per field.
      e.fieldErrors().forEach(fe -> System.err.println(fe.field() + " " + fe.message()));
      System.err.println("Nothing changed.");
      System.exit(1);
    } catch (ErpException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    } finally {
      Arrays.fill(first == null ? new char[0] : first, '\0');
      Arrays.fill(second == null ? new char[0] : second, '\0');
    }
  }
}

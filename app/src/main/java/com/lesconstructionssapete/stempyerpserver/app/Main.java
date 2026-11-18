package com.lesconstructionssapete.stempyerpserver.app;

import java.sql.SQLException;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationEnvironment;
import com.lesconstructionssapete.stempyerpserver.app.config.ApplicationProperties;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseMigrator;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.ItemService;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersModule;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.web.DevAuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityQueryEndpoint;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthChecks;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthRoutes;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

public class Main {

  /**
   * Every business module, in dependency order: a module may only use what the
   * modules listed BEFORE it registered (entities, services).
   */
  private static List<ERPModule> modules() {
    return List.of(
        new UsersModule(),
        new ItemService());
  }

  public static void main(String[] args) {

    List<ERPModule> modules = modules();

    // Entity definitions first: a broken one fails here, before touching the DB.
    var entities = new EntityRegistry();
    modules.forEach(m -> m.registerEntities(entities));
    entities.freeze();

    HikariDataSource dataSource;

    var db = DatabaseConfig.of(
        ApplicationEnvironment.get("DATABASE_HOST"),
        ApplicationEnvironment.getInt("DATABASE_PORT"),
        ApplicationEnvironment.get("DATABASE_NAME"),
        ApplicationProperties.getByPrefix("database.url.params"),
        ApplicationEnvironment.get("DATABASE_USER"),
        ApplicationEnvironment.get("DATABASE_PASSWORD"));

    DatabaseMigrator.migrate(
        db.jdbcUrl(),
        db.user(),
        db.password());

    dataSource = DataSources.create(db);

    var uowFactory = new UnitOfWorkFactory(dataSource);

    // try ( dataSource = DataSources.create(db)) {
    // UnitOfWorkFactory uowFactory = new UnitOfWorkFactory(dataSource);
    // var result = uowFactory.execute(uow -> postingServices.post(uow, "INV1234",
    // requestContext));
    // }

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

    // Until the JWT AuthFilter exists, local testing authenticates through
    // X-Dev-* headers. Off unless explicitly enabled.
    var authFilter = ApplicationEnvironment.getBoolean("DEV_AUTH_ENABLED", false)
        ? new DevAuthFilter()
        : null;

    var exceptionMapper = new ExceptionMapper(new ErrorTranslator());
    var webServer = new WebServer(WebServerConfig.ofPort(8081), exceptionMapper,
        authFilter == null ? null : authFilter::handle);

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
    Runtime.getRuntime().addShutdownHook(new Thread(webServer::stop));

  }
}

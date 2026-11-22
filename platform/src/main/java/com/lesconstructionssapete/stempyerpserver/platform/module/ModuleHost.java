package com.lesconstructionssapete.stempyerpserver.platform.module;

import java.util.List;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.security.AuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityQueryEndpoint;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthChecks;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthRoutes;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;

/**
 * Drives the modules through their phases, the same way for the server, CLI
 * commands and integration tests:
 *
 * <pre>
 * var host = new ModuleHost(modules);        1. entities (fails fast on a broken DEF, before any DB work)
 *   ... migrate, open the pool ...
 * host.start(uowFactory);                    2. platform services + module services, then 3. event handlers
 * host.services().get(UsersApi.class)        (a CLI command stops here)
 * host.startWebServer(config);               4. health + module routes behind the AuthFilter
 * </pre>
 *
 * Modules are driven in list order: a module may only use what the modules
 * BEFORE it registered (services, and entities its relations point at).
 */
public final class ModuleHost {

  private final List<ERPModule> modules;
  private final EntityRegistry entities = new EntityRegistry();
  private UnitOfWorkFactory uowFactory;
  private ServiceRegistry services;

  public ModuleHost(List<ERPModule> modules) {
    this.modules = List.copyOf(modules);
    this.modules.forEach(m -> m.registerEntities(entities));
    entities.freeze(); // throws on a relation to an unregistered entity
  }

  public EntityRegistry entities() {
    return entities;
  }

  /**
   * Publishes the platform services every module may use (UnitOfWorkFactory,
   * EventBus, EntityRegistry, EntityQueryEndpoint), then each module's own, then
   * wires event handlers. Both registries are frozen afterwards.
   */
  public synchronized ServiceRegistry start(UnitOfWorkFactory uowFactory) {
    if (services != null) {
      throw new IllegalStateException("ModuleHost already started");
    }
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    var eventBus = new EventBus();
    var registry = new ServiceRegistry();
    registry.register(UnitOfWorkFactory.class, uowFactory);
    registry.register(EventBus.class, eventBus);
    registry.register(EntityRegistry.class, entities);
    registry.register(EntityQueryEndpoint.class, new EntityQueryEndpoint(uowFactory, QueryLimits.defaults()));
    modules.forEach(m -> m.registerServices(registry));
    registry.freeze();

    modules.forEach(m -> m.registerEventHandlers(eventBus, registry));
    eventBus.freeze();

    services = registry;
    return registry;
  }

  public synchronized ServiceRegistry services() {
    if (services == null) {
      throw new IllegalStateException("ModuleHost not started");
    }
    return services;
  }

  /**
   * Starts the HTTP API: /health (with a database check) and every module's
   * routes, authenticated through the SessionAuthenticator a module published.
   */
  public WebServer startWebServer(WebServerConfig config) {
    ServiceRegistry s = services();
    var health = new HealthChecks().add("database",
        () -> uowFactory.executeReadOnly(uow -> Jdbc.queryFirst(uow, "SELECT 1", rs -> rs.getInt(1))));
    var authFilter = new AuthFilter(s.get(SessionAuthenticator.class));
    var server = new WebServer(config, new ExceptionMapper(new ErrorTranslator()), authFilter::handle);
    server.start(routes -> {
      HealthRoutes.register(routes, health);
      modules.forEach(m -> m.registerRoutes(routes, s));
    });
    return server;
  }
}

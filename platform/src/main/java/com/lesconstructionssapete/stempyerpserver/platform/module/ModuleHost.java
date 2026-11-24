package com.lesconstructionssapete.stempyerpserver.platform.module;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.Outbox;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxSettings;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal.JdbcOutboxWriter;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal.OutboxPruneJob;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.SchedulerSettings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.PruneRunsJob;
import com.lesconstructionssapete.stempyerpserver.platform.security.AuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthChecks;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthRoutes;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;

/**
 * Drives the modules through their phases, the same way for the server, CLI
 * commands and integration tests:
 *
 * <pre>
 * var host = new ModuleHost(modules);        0. permissions, 1. entities (fail fast on a broken DEF or an
 *                                            undeclared field permission, before any DB work)
 *   ... migrate, open the pool ...
 * host.start(uowFactory);                    2. platform services + module services, then 3. event handlers
 * host.services().get(UsersApi.class)        (a CLI command stops here)
 * host.startWebServer(config);               4. health + module routes behind the AuthFilter (session from
 *                                            SessionAuthenticator, company + permissions from CompanyAccess)
 * host.startScheduler(settings);             5. server mode only: start running jobs (registered in start())
 * host.startOutbox(settings);                6. server mode only: start delivering what async event
 *                                            subscriptions wrote to the outbox (CLI commands still write rows)
 * </pre>
 *
 * Modules are driven in list order: a module may only use what the modules
 * BEFORE it registered (services, and entities its relations point at).
 */
public final class ModuleHost {

  private final List<ERPModule> modules;
  private final PermissionRegistry permissions = new PermissionRegistry();
  private final EntityRegistry entities = new EntityRegistry();
  private UnitOfWorkFactory uowFactory;
  private ServiceRegistry services;
  private final Scheduler scheduler = new Scheduler();
  private Outbox outbox;

  public ModuleHost(List<ERPModule> modules) {
    this.modules = List.copyOf(modules);
    this.modules.forEach(m -> m.registerPermissions(permissions));
    permissions.freeze();
    this.modules.forEach(m -> m.registerEntities(entities));
    entities.freeze(permissions::contains); // throws on a bad relation or undeclared field permission
  }

  public PermissionRegistry permissions() {
    return permissions;
  }

  public EntityRegistry entities() {
    return entities;
  }

  /**
   * Publishes the platform services every module may use (UnitOfWorkFactory,
   * EventBus, EntityRegistry, PermissionRegistry, PermissionChecker,
   * EntityEndpoints, Scheduler, Outbox), then each module's own, then
   * wires event handlers (synchronous and async), then job types. The
   * registries are frozen afterwards. Nothing runs yet: see startScheduler()
   * and startOutbox().
   */
  public synchronized ServiceRegistry start(UnitOfWorkFactory uowFactory) {
    if (services != null) {
      throw new IllegalStateException("ModuleHost already started");
    }
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    // Async subscriptions write their outbox rows with the publisher's UnitOfWork.
    var eventBus = new EventBus(new JdbcOutboxWriter(JsonMapperFactory.newObjectMapper()));
    outbox = new Outbox(eventBus);
    var registry = new ServiceRegistry();
    registry.register(UnitOfWorkFactory.class, uowFactory);
    registry.register(EventBus.class, eventBus);
    registry.register(EntityRegistry.class, entities);
    var checker = new PermissionChecker(permissions);
    registry.register(PermissionRegistry.class, permissions);
    registry.register(PermissionChecker.class, checker);
    // Whole seconds, like a MySQL TIMESTAMP, so a time written equals the time read back.
    Clock clock = Clock.tick(Clock.systemUTC(), Duration.ofSeconds(1));
    registry.register(EntityEndpoints.class,
        new EntityEndpoints(uowFactory, QueryLimits.defaults(), checker, eventBus, clock));
    registry.register(Scheduler.class, scheduler);
    registry.register(Outbox.class, outbox);
    modules.forEach(m -> m.registerServices(registry));
    registry.freeze();

    modules.forEach(m -> m.registerEventHandlers(eventBus, registry));
    eventBus.freeze();

    // The scheduler's own housekeeping first, then the modules' jobs.
    scheduler.register(PruneRunsJob.type(uowFactory, () -> scheduler.settings().runRetention()));
    scheduler.register(OutboxPruneJob.type(uowFactory, outbox::settings));
    modules.forEach(m -> m.registerJobs(scheduler, registry));
    scheduler.freeze(permissions::contains);

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
   * Starts running jobs, unless {@code settings.enabled()} is false. Server
   * mode only: CLI commands and most tests never call it. Call
   * {@code scheduler.stop()} before closing the connection pool.
   */
  public Scheduler startScheduler(SchedulerSettings settings) {
    ServiceRegistry s = services();
    if (!settings.enabled()) {
      LoggerFactory.getLogger(ModuleHost.class).info("Scheduler disabled (scheduler.enabled=false)");
      return scheduler;
    }
    scheduler.start(settings, uowFactory, s.get(SystemActor.class), s.get(CompanyAccess.class), permissions);
    return scheduler;
  }

  /**
   * Starts delivering the outbox, unless {@code settings.enabled()} is false.
   * Server mode only: CLI commands and most tests never call it. Call
   * {@code outbox.stop()} before closing the connection pool.
   */
  public Outbox startOutbox(OutboxSettings settings) {
    ServiceRegistry s = services();
    outbox.start(settings, uowFactory, s.get(SystemActor.class), permissions);
    return outbox;
  }

  /**
   * Starts the HTTP API: /health (with a database check) and every module's
   * routes, authenticated through the SessionAuthenticator and CompanyAccess a
   * module published.
   */
  public WebServer startWebServer(WebServerConfig config) {
    ServiceRegistry s = services();
    var health = new HealthChecks().add("database",
        () -> uowFactory.executeReadOnly(uow -> Jdbc.queryFirst(uow, "SELECT 1", rs -> rs.getInt(1))));
    var authFilter = new AuthFilter(s.get(SessionAuthenticator.class), s.get(CompanyAccess.class));
    var server = new WebServer(config, new ExceptionMapper(new ErrorTranslator()), authFilter::handle);
    server.start(routes -> {
      HealthRoutes.register(routes, health);
      modules.forEach(m -> m.registerRoutes(routes, s));
    });
    return server;
  }
}

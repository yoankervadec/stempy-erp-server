package com.lesconstructionssapete.stempyerpserver.platform.module;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditPruneJob;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditSettings;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditTrail;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.Outbox;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxSettings;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal.JdbcOutboxWriter;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal.OutboxPruneJob;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.idempotency.Idempotency;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.internal.CounterRepository;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.internal.JdbcDocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.internal.SeriesRepository;
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
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.DocumentEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthChecks;
import com.lesconstructionssapete.stempyerpserver.platform.web.HealthRoutes;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.MetaRoutes;
import com.lesconstructionssapete.stempyerpserver.platform.web.ResourceCatalog;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;

/**
 * Drives the modules through their phases, the same way for the server, CLI
 * commands and integration tests:
 *
 * <pre>
 * var host = new ModuleHost(modules);        0. permissions, 1. entities (fail fast on a broken DEF or an
 *                                            undeclared field permission, before any DB work), then
 *                                            document types (fail fast on a bad declaration)
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
  private final DocumentTypes documentTypes = new DocumentTypes();
  private final Messages messages;
  private UnitOfWorkFactory uowFactory;
  private ServiceRegistry services;
  private final Scheduler scheduler = new Scheduler();
  private Outbox outbox;
  private volatile AuditSettings auditSettings = AuditSettings.defaults();

  public ModuleHost(List<ERPModule> modules) {
    this.modules = List.copyOf(modules);
    permissions.declare(AuditLog.READ_PERMISSION,
        "See the change history of records (who changed what, when), on top of the record's read permission");
    this.modules.forEach(m -> m.registerPermissions(permissions));
    permissions.freeze();
    this.modules.forEach(m -> m.registerEntities(entities));
    entities.freeze(permissions::contains); // throws on a bad relation or undeclared field permission
    this.modules.forEach(m -> m.registerDocumentTypes(documentTypes)); // throws on a bad declaration
    documentTypes.freeze();
    List<String> bundles = new ArrayList<>();
    bundles.add(PLATFORM_BUNDLE);
    this.modules.forEach(m -> bundles.add(m.name()));
    this.messages = Messages.load(ModuleHost.class.getClassLoader(), bundles); // throws on a key defined twice
  }

  /** The platform's own texts: i18n/platform.yaml. */
  public static final String PLATFORM_BUNDLE = "platform";

  /** Every text, from the platform's bundles and one pair per module (docs/ARCHITECTURE.md §23.5). */
  public Messages messages() {
    return messages;
  }

  public PermissionRegistry permissions() {
    return permissions;
  }

  public DocumentTypes documentTypes() {
    return documentTypes;
  }

  public EntityRegistry entities() {
    return entities;
  }

  /**
   * Publishes the platform services every module may use (UnitOfWorkFactory,
   * EventBus, EntityRegistry, PermissionRegistry, PermissionChecker,
   * Messages, EntityEndpoints, DocumentEndpoints, ResourceCatalog, Scheduler, Outbox, AuditLog, DocumentTypes, DocumentNumbers, Idempotency), then each module's
   * own, then
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
    var auditLog = new AuditLog(JsonMapperFactory.newObjectMapper());
    registry.register(AuditLog.class, auditLog);
    var catalog = new ResourceCatalog();
    registry.register(ResourceCatalog.class, catalog);
    registry.register(Messages.class, messages);
    var entityEndpoints = new EntityEndpoints(uowFactory, QueryLimits.defaults(), checker,
        eventBus, clock, auditLog, () -> registry.find(UserDirectory.class), catalog, messages);
    registry.register(EntityEndpoints.class, entityEndpoints);
    registry.register(DocumentEndpoints.class, new DocumentEndpoints(uowFactory, QueryLimits.defaults(), checker,
        eventBus, clock, catalog, messages, entityEndpoints));
    registry.register(Scheduler.class, scheduler);
    registry.register(Outbox.class, outbox);
    registry.register(DocumentTypes.class, documentTypes);
    var idempotency = new Idempotency(clock);
    registry.register(Idempotency.class, idempotency);
    registry.register(DocumentNumbers.class, new JdbcDocumentNumbers(documentTypes, uowFactory,
        new SeriesRepository(), new CounterRepository()));
    modules.forEach(m -> m.registerServices(registry));
    registry.freeze();

    // The audit trail first: every generic write is recorded in its own transaction.
    eventBus.subscribe(EntityChanged.class, new AuditTrail(auditLog, entities));
    modules.forEach(m -> m.registerEventHandlers(eventBus, registry));
    eventBus.freeze();

    // The scheduler's own housekeeping first, then the modules' jobs.
    scheduler.register(PruneRunsJob.type(uowFactory, () -> scheduler.settings().runRetention()));
    scheduler.register(OutboxPruneJob.type(uowFactory, outbox::settings));
    scheduler.register(AuditPruneJob.type(uowFactory, registry.get(AuditLog.class), () -> auditSettings,
        Clock.systemUTC()));
    scheduler.register(idempotency.pruneJob(uowFactory));
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
   * The audit.* settings (audit.retention, used by the audit.prune job). Call
   * before startScheduler(); defaults apply otherwise.
   */
  public void configureAudit(AuditSettings settings) {
    this.auditSettings = Objects.requireNonNull(settings, "settings");
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
   * Starts the HTTP API: /health (with a database check), every module's
   * routes (recording their resources in the ResourceCatalog) and GET /meta, authenticated through the SessionAuthenticator and CompanyAccess a
   * module published.
   */
  public WebServer startWebServer(WebServerConfig config) {
    ServiceRegistry s = services();
    var health = new HealthChecks().add("database",
        () -> uowFactory.executeReadOnly(uow -> Jdbc.queryFirst(uow, "SELECT 1", rs -> rs.getInt(1))));
    var authFilter = new AuthFilter(s.get(SessionAuthenticator.class), s.get(CompanyAccess.class));
    var server = new WebServer(config, new ExceptionMapper(new ErrorTranslator(), messages), authFilter::handle);
    server.start(routes -> {
      HealthRoutes.register(routes, health);
      ResourceCatalog catalog = s.get(ResourceCatalog.class);
      modules.forEach(m -> {
        catalog.enterModule(m.name());
        m.registerRoutes(routes, s);
      });
      catalog.freeze();
      List<String> missing = catalog.missingLabels(messages);
      if (!missing.isEmpty()) {
        throw new IllegalStateException("Action or layout texts missing from the i18n files:\n" + String.join("\n", missing));
      }
      new MetaRoutes(catalog, s.get(PermissionChecker.class), messages).register(routes);
    });
    return server;
  }
}

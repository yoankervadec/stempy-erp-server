package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.CreateSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.FindSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.ListCountersOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.ListSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.SetNextNumberOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.UpdateSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Administration of document numbering (docs/ARCHITECTURE.md §21.7): number
 * series and their counters, under /numbering. Numbers themselves are handed
 * out by platform.numbering (DocumentNumbers); this module only edits its
 * tables and checks who may.
 *
 * Permissions: numbering.series.read, numbering.series.manage, for the
 * caller's company.
 */
public class NumberingModule implements ERPModule {

  private final Clock clock;
  private NumberingService service; // built in registerServices, used by registerRoutes

  public NumberingModule(Clock clock) {
    // Whole seconds: what a TIMESTAMP column stores.
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofSeconds(1));
  }

  @Override
  public String name() {
    return "system.numbering";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    permissions
        .declare(NumberingService.READ, "See the company's number series, their counters and the document types")
        .declare(NumberingService.MANAGE,
            "Create and change the company's number series, and move their counters up");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    // none: series have their own routes (rules on patterns, defaults and counters)
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
    var repository = new NumberSeriesAdminRepository();
    var audit = serviceRegistry.get(AuditLog.class);
    service = new NumberingService(serviceRegistry.get(UnitOfWorkFactory.class),
        serviceRegistry.get(DocumentTypes.class), serviceRegistry.get(PermissionChecker.class), clock,
        new ListSeriesOperation(repository), new FindSeriesOperation(repository),
        new CreateSeriesOperation(repository, audit), new UpdateSeriesOperation(repository, audit),
        new ListCountersOperation(repository), new SetNextNumberOperation(repository, audit), audit,
        serviceRegistry.find(UserDirectory.class)); // the users module, before this one, publishes it
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    new NumberingController(service).register(routes);
  }
}

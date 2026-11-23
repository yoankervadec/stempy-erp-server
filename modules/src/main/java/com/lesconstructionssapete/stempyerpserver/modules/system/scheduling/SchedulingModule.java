package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.CreateScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.DeleteScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.FindRunOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.FindScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.ListRunsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.ListSchedulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.QueueManualRunOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.UpdateScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Administration of the scheduler: job types, schedules (scheduled_jobs),
 * manual runs and run history, under /scheduler. The scheduler itself is
 * platform code (platform.scheduler); this module only edits its tables and
 * checks who may.
 *
 * Permissions: scheduler.schedule.read, scheduler.schedule.manage, scheduler.schedule.run for the
 * caller's company; scheduler.system.manage for install-wide schedules.
 */
public class SchedulingModule implements ERPModule {

  private final Clock clock;
  private SchedulingService service; // built in registerServices, used by registerRoutes

  public SchedulingModule(Clock clock) {
    // Whole seconds: what a TIMESTAMP column stores.
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofSeconds(1));
  }

  @Override
  public String name() {
    return "system.scheduling";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    permissions
        .declare(SchedulingService.READ, "See the company's schedules, their run history and the job types")
        .declare(SchedulingService.MANAGE, "Create, change and delete the company's schedules")
        .declare(SchedulingService.RUN, "Run the company's schedules now (the run acts as you)")
        .declare(SchedulingService.SYSTEM, "See, change and run install-wide (system) schedules");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    // none: schedules have their own routes (company_id may be NULL, which the entity framework doesn't model)
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
    var repository = new ScheduleAdminRepository();
    service = new SchedulingService(serviceRegistry.get(UnitOfWorkFactory.class),
        serviceRegistry.get(Scheduler.class), serviceRegistry.get(PermissionChecker.class), clock,
        new ListSchedulesOperation(repository), new FindScheduleOperation(repository),
        new CreateScheduleOperation(repository), new UpdateScheduleOperation(repository),
        new DeleteScheduleOperation(repository), new QueueManualRunOperation(repository),
        new ListRunsOperation(repository), new FindRunOperation(repository));
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    new SchedulingController(service).register(routes);
  }
}

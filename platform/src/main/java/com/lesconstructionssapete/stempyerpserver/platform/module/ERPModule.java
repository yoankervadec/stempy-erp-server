package com.lesconstructionssapete.stempyerpserver.platform.module;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * A business module. Main calls every module through each phase, in order:
 * entities, services, event handlers, routes, jobs. Override only the phases
 * the module takes part in; the others do nothing.
 */
public interface ERPModule {

  String name();

  void registerEntities(EntityRegistry entityRegistry);

  default void registerServices(ServiceRegistry serviceRegistry) {
  }

  default void registerEventHandlers(EventBus eventBus, ServiceRegistry serviceRegistry) {
  }

  default void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
  }

  default void registerJobs(Scheduler scheduler, ServiceRegistry serviceRegistry) {
  }

}

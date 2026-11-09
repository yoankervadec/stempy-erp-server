package com.lesconstructionssapete.stempyerpserver.platform.module;

import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

public interface ERPModule {

  String name();

  void registerServices(ServiceRegistry serviceRegistry);

  void registerRoutes(Routes routes, ServiceRegistry serviceRegistry);

  void registerEventHandlers(EventBus eventBus, ServiceRegistry serviceRegistry);

  default void registerJobs(Scheduler scheduler, ServiceRegistry serviceRegistry) {
  }

}

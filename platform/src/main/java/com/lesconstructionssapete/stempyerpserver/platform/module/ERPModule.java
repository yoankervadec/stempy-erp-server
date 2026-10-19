package com.lesconstructionssapete.stempyerpserver.platform.module;

import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;

import io.javalin.Javalin;

public interface ERPModule {

  String name();

  void registerServices(ServiceRegistry serviceRegistry);

  void registerRoutes(Javalin app, ServiceRegistry serviceRegistry);

  void registerEventHandlers(EventBus eventBus, ServiceRegistry serviceRegistry);

  default void registerJobs(Scheduler scheduler, ServiceRegistry serviceRegistry) {
  }

}

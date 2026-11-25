package com.lesconstructionssapete.stempyerpserver.platform.module;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * A business module. ModuleHost calls every module through each phase, in order:
 * permissions, entities, document types, services, event handlers, jobs (registered in every mode, run only
 * in server mode), routes. Override only the phases the module takes part in; the others do
 * nothing.
 */
public interface ERPModule {

  String name();

  /**
   * Declares every permission the module checks, field permissions included.
   * Runs first, so entity field permissions can be validated against it.
   */
  default void registerPermissions(PermissionRegistry permissions) {
  }

  void registerEntities(EntityRegistry entityRegistry);

  /**
   * Declares the kinds of document the module numbers (docs/ARCHITECTURE.md
   * §21.4). Runs before services, with no database.
   */
  default void registerDocumentTypes(DocumentTypes documentTypes) {
  }

  default void registerServices(ServiceRegistry serviceRegistry) {
  }

  default void registerEventHandlers(EventBus eventBus, ServiceRegistry serviceRegistry) {
  }

  default void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
  }

  default void registerJobs(Scheduler scheduler, ServiceRegistry serviceRegistry) {
  }

}

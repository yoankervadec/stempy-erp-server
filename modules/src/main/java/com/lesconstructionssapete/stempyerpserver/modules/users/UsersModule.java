package com.lesconstructionssapete.stempyerpserver.modules.users;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Users. For now it only registers the User entity, which most other entities
 * point at through created_by / updated_by. List it before those modules in
 * Main.
 */
public class UsersModule implements ERPModule {

  @Override
  public String name() {
    return "users";
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(User.class);
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
  }

  @Override
  public void registerEventHandlers(EventBus eventBus, ServiceRegistry serviceRegistry) {
  }
}

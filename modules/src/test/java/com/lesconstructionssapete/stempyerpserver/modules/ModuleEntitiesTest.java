package com.lesconstructionssapete.stempyerpserver.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.ItemsModule;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersModule;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinitionScanner;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;

/**
 * Catches entity wiring mistakes at build time instead of at startup.
 * Keep MODULES in the same order as Main.modules().
 */
class ModuleEntitiesTest {

  private static final List<ERPModule> MODULES = List.of(new UsersModule(), new ItemsModule());

  @Test
  void allModuleEntitiesRegisterAndFreeze() {
    EntityRegistry registry = new EntityRegistry();
    MODULES.forEach(m -> m.registerEntities(registry));
    registry.freeze(); // throws on a relation to an unregistered entity
  }

  @Test
  void everyEntityClassIsRegisteredBySomeModule() {
    EntityRegistry registry = new EntityRegistry();
    MODULES.forEach(m -> m.registerEntities(registry));

    Set<String> registered = registry.all().stream().map(EntityDefinition::name).collect(Collectors.toSet());
    Set<String> declared = EntityDefinitionScanner.findEntityClasses("com.lesconstructionssapete.stempyerpserver.modules")
        .stream().map(c -> EntityRegistry.defOf(c).name()).collect(Collectors.toSet());

    assertEquals(declared, registered, "entity classes not registered in any module's registerEntities()");
  }
}

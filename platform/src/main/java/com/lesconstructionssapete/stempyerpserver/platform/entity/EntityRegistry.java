package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every EntityDef in the application. Modules register theirs from their
 * ERPModule hook; freeze() runs once at startup and fails fast on a broken
 * definition.
 */
public class EntityRegistry {

  private final Map<String, EntityDefinition<?>> byName = new LinkedHashMap<>();
  private boolean frozen;

  public EntityRegistry register(EntityDefinition<?> def) {
    if (frozen) {
      throw new IllegalStateException("Registry is frozen");
    }
    if (byName.putIfAbsent(def.name(), def) != null) {
      throw new IllegalStateException("Entity already registered: " + def.name());
    }
    return this;
  }

  /**
   * Validates every definition and that every relation points at a registered
   * entity.
   */
  public void freeze() {
    for (EntityDefinition<?> def : byName.values()) {
      def.validateDefinition();
      for (Relation<?, ?> r : def.relations()) {
        EntityDefinition<?> target = r.target();
        if (byName.get(target.name()) != target) {
          throw new IllegalStateException(def.name() + "." + r.name()
              + " points at unregistered entity " + target.name());
        }
      }
    }
    frozen = true;
  }

  public Optional<EntityDefinition<?>> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public Collection<EntityDefinition<?>> all() {
    return Collections.unmodifiableCollection(byName.values());
  }

}

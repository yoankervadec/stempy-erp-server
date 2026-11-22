package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Every EntityDef in the application. Modules register theirs from their
 * ERPModule hook; freeze() runs once at startup and fails fast on a broken
 * definition.
 */
public class EntityRegistry {

  private final Map<String, EntityDefinition<?>> byName = new LinkedHashMap<>();
  private boolean frozen;

  /**
   * Registers entity classes by reading their
   * {@code public static final EntityDefinition<?> DEF} constant.
   * Intended to be called from each module's startup hook with that module's
   * entity classes.
   */
  public EntityRegistry registerAll(Class<?>... entityClasses) {
    for (Class<?> clazz : entityClasses) {
      register(defOf(clazz));
    }
    return this;
  }

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
   * Reads the DEF constant of an entity class; fails with a clear message if the
   * convention is broken.
   */
  public static EntityDefinition<?> defOf(Class<?> entityClass) {
    try {
      java.lang.reflect.Field f = entityClass.getField("DEF");
      int mod = f.getModifiers();
      if (!java.lang.reflect.Modifier.isStatic(mod)
          || !java.lang.reflect.Modifier.isFinal(mod)
          || !EntityDefinition.class.isAssignableFrom(f.getType())) {
        throw new IllegalStateException(entityClass.getName()
            + ".DEF must be 'public static final EntityDefinition<...>'");
      }
      return (EntityDefinition<?>) f.get(null);
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(entityClass.getName() + " has no public static DEF constant", e);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Cannot read " + entityClass.getName() + ".DEF", e);
    }
  }

  /**
   * Validates every definition and that every relation points at a registered
   * entity. Field permissions aren't checked; ModuleHost uses
   * freeze(Predicate).
   */
  public void freeze() {
    freeze(permission -> true);
  }

  /**
   * As freeze(), and also fails on a field read/write permission that no module
   * declared (a typo there would hide the field from everyone, silently).
   */
  public void freeze(Predicate<String> permissionDeclared) {
    for (EntityDefinition<?> def : byName.values()) {
      def.validateDefinition();
      for (Field<?, ?> f : def.fields()) {
        for (String p : new String[] { f.readPermission(), f.writePermission() }) {
          if (p != null && !permissionDeclared.test(p)) {
            throw new IllegalStateException(def.name() + "." + f.name()
                + " uses permission '" + p + "', which no module declares");
          }
        }
      }
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

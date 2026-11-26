package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;

/**
 * Every resource the UI can show (docs/ARCHITECTURE.md §23.3): an entity, the
 * path its routes live under, the module that registered it, and the
 * permission each action needs. GET /meta (MetaRoutes) describes it to each
 * caller.
 *
 * Filled while the routes are registered: EntityEndpoints.register() adds its
 * resource, EntityEndpoints.declareWrite() adds a hand-written write that
 * keeps the entity's body shape. ModuleHost names the module before each
 * module registers its routes, then freezes the catalog.
 *
 * A resource is keyed by its entity's name ("product", "glAccount"), unique
 * in the EntityRegistry; one entity is one resource.
 */
public final class ResourceCatalog {

  /**
   * @param key         the entity name, stable: the UI and the label bundles
   *                    use it
   * @param permissions the permission each available action needs; READ also
   *                    serves GET {id}/history
   */
  public record Resource(String key, String module, String path, EntityDefinition<?> entity,
      Map<Action, String> permissions) {

    public Resource {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(module, "module");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(entity, "entity");
      permissions = Collections.unmodifiableMap(new EnumMap<>(permissions));
    }

    public Optional<String> permission(Action action) {
      return Optional.ofNullable(permissions.get(action));
    }
  }

  private final Map<String, Resource> byKey = new LinkedHashMap<>();
  private String module;
  private volatile boolean frozen;

  /** The module whose routes are registered next. Called by ModuleHost. */
  public synchronized void enterModule(String name) {
    requireOpen();
    this.module = Objects.requireNonNull(name, "name");
  }

  /** From EntityEndpoints.register(). */
  synchronized void add(String path, EntityDefinition<?> entity, String permissionPrefix, Set<Action> actions) {
    requireOpen();
    if (module == null) {
      throw new IllegalStateException("No module is registering routes; call enterModule() first");
    }
    if (byKey.containsKey(entity.name())) {
      throw new IllegalStateException("Entity " + entity.name() + " is registered twice ("
          + byKey.get(entity.name()).path() + " and " + path + ")");
    }
    Map<Action, String> permissions = new EnumMap<>(Action.class);
    actions.forEach(a -> permissions.put(a, a.permission(permissionPrefix)));
    byKey.put(entity.name(), new Resource(entity.name(), module, path, entity, permissions));
  }

  /** From EntityEndpoints.declareWrite(). */
  synchronized void declareWrite(EntityDefinition<?> entity, Action action, String permission) {
    requireOpen();
    if (action == Action.READ) {
      throw new IllegalArgumentException("READ is not a write; register it through EntityEndpoints.register()");
    }
    Resource r = byKey.get(entity.name());
    if (r == null) {
      throw new IllegalStateException("Entity " + entity.name()
          + " has no resource yet; register its READ routes first");
    }
    if (r.permissions().containsKey(action)) {
      throw new IllegalStateException(entity.name() + " already serves " + action);
    }
    Map<Action, String> permissions = new EnumMap<>(r.permissions());
    permissions.put(action, Objects.requireNonNull(permission, "permission"));
    byKey.put(r.key(), new Resource(r.key(), r.module(), r.path(), r.entity(), permissions));
  }

  public void freeze() {
    frozen = true;
  }

  /** In registration order (module order, then each module's own order). */
  public Collection<Resource> all() {
    return Collections.unmodifiableCollection(byKey.values());
  }

  public Optional<Resource> find(String key) {
    return Optional.ofNullable(byKey.get(key));
  }

  /** The resource serving {@code entity}, if one does. */
  public Optional<Resource> forEntity(EntityDefinition<?> entity) {
    Resource r = byKey.get(entity.name());
    return r != null && r.entity() == entity ? Optional.of(r) : Optional.empty();
  }

  /**
   * The resource a field's value points at: the entity it declares with
   * Field.references(), else the target of a many-to-one relation whose
   * foreign key is the field's column; empty when that entity isn't a
   * resource. Used by GET /meta (type "reference") and the rows' _refs.
   */
  public Optional<Resource> referenceOf(EntityDefinition<?> entity, Field<?, ?> field) {
    Optional<EntityDefinition<?>> declared = field.referencedEntity();
    if (declared.isPresent()) {
      return forEntity(declared.get());
    }
    for (Relation<?, ?> rel : entity.relations()) {
      if (rel.kind() == Relation.Kind.MANY_TO_ONE && rel.foreignKey().equals(field.column())) {
        return forEntity(rel.target());
      }
    }
    return Optional.empty();
  }

  private void requireOpen() {
    if (frozen) {
      throw new IllegalStateException("ResourceCatalog is frozen; register resources in registerRoutes()");
    }
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.LabelKeys;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
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
   * @param rules       what a record's state allows; null: nothing beyond
   *                    permissions
   * @param actions     what it does besides create / update / delete
   * @param layout      how a record is laid out; null: derived (MetaRoutes)
   */
  public record Resource(String key, String module, String path, EntityDefinition<?> entity,
      Map<Action, String> permissions, RecordRules rules, List<ResourceAction> actions, RecordLayout layout) {

    public Resource {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(module, "module");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(entity, "entity");
      permissions = Collections.unmodifiableMap(new EnumMap<>(permissions));
      actions = List.copyOf(actions);
    }

    Resource with(Map<Action, String> permissions, RecordRules rules, List<ResourceAction> actions) {
      return new Resource(key, module, path, entity, permissions, rules, actions, layout);
    }

    Resource withLayout(RecordLayout layout) {
      return new Resource(key, module, path, entity, permissions, rules, actions, layout);
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
    byKey.put(entity.name(), new Resource(entity.name(), module, path, entity, permissions, null, List.of(), null));
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
    byKey.put(r.key(), r.with(permissions, r.rules(), r.actions()));
  }

  /** From EntityEndpoints.rules(). */
  synchronized void rules(EntityDefinition<?> entity, RecordRules rules) {
    Resource r = existing(entity);
    if (r.rules() != null) {
      throw new IllegalStateException(entity.name() + " already has rules");
    }
    byKey.put(r.key(), r.with(r.permissions(), Objects.requireNonNull(rules, "rules"), r.actions()));
  }

  /** From EntityEndpoints.layout(). */
  synchronized void layout(EntityDefinition<?> entity, RecordLayout layout) {
    Resource r = existing(entity);
    if (layout.entity() != entity) {
      throw new IllegalArgumentException("A layout of " + layout.entity().name() + " given for " + entity.name());
    }
    if (r.layout() != null) {
      throw new IllegalStateException(entity.name() + " already has a layout");
    }
    byKey.put(r.key(), r.withLayout(layout));
  }

  /** From EntityEndpoints.action(). */
  synchronized void action(EntityDefinition<?> entity, ResourceAction action) {
    Resource r = existing(entity);
    if (r.actions().stream().anyMatch(a -> a.name().equals(action.name()))) {
      throw new IllegalStateException(entity.name() + " already has an action " + action.name());
    }
    List<ResourceAction> actions = new ArrayList<>(r.actions());
    actions.add(action);
    byKey.put(r.key(), r.with(r.permissions(), r.rules(), actions));
  }

  /**
   * The texts the declared actions and layouts need and some language lacks
   * (docs/ARCHITECTURE.md §23.5): an action's label, its confirmation (when
   * it asks one), each parameter's label; a layout's section and collection
   * labels. Both are declared with the routes, so ModuleHost checks this at
   * startup rather than a test of the i18n files.
   */
  public List<String> missingLabels(Messages messages) {
    List<String> missing = new ArrayList<>();
    for (Language language : Language.values()) {
      Map<String, String> texts = messages.all(language);
      for (Resource r : byKey.values()) {
        for (ResourceAction a : r.actions()) {
          List<String[]> keys = new ArrayList<>();
          keys.add(new String[] { LabelKeys.action(r.key(), a.name()) });
          if (a.needsConfirmation()) {
            keys.add(new String[] { LabelKeys.actionConfirm(r.key(), a.name()) });
          }
          a.params().forEach(p -> keys.add(LabelKeys.actionParam(r.key(), a.name(), p.name())));
          for (String[] alternatives : keys) {
            check(texts, alternatives, language, missing);
          }
        }
        if (r.layout() != null) {
          for (RecordLayout.Section section : r.layout().sections()) {
            check(texts, LabelKeys.section(r.key(), section.name()), language, missing);
          }
          for (RecordLayout.Collection c : r.layout().collections()) {
            check(texts, LabelKeys.collection(r.key(), c.name(), c.relation().target().name()), language, missing);
          }
        }
      }
    }
    return missing;
  }

  private static void check(Map<String, String> texts, String[] alternatives, Language language,
      List<String> missing) {
    if (Arrays.stream(alternatives).noneMatch(texts::containsKey)) {
      missing.add(language.code() + ": " + String.join(" or ", alternatives));
    }
  }

  private Resource existing(EntityDefinition<?> entity) {
    requireOpen();
    Resource r = byKey.get(entity.name());
    if (r == null) {
      throw new IllegalStateException("Entity " + entity.name()
          + " has no resource yet; register its READ routes first");
    }
    return r;
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

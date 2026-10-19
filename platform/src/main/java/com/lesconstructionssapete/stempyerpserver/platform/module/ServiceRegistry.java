package com.lesconstructionssapete.stempyerpserver.platform.module;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Startup-time lookup of shared services. It is a composition-root tool, NOT
 * something
 * orchestrators, operations or repositories should ever receive.
 *
 * What goes in:
 * - Platform services built by Main (UnitOfWorkFactory, EventBus, JwtService,
 * SettingsService...).
 * - Each module's explicit PUBLIC API (e.g. GeneralLedgerApi), published in
 * registerServices().
 *
 * What stays out: repositories, operations, orchestrators and listeners. Those
 * are module-internal;
 * putting them here would be a back door around the module boundaries.
 *
 * Usage pattern: a module's XxxModule class resolves what it needs from the
 * registry ONCE,
 * then hands plain constructor arguments to its internals. The registry never
 * travels deeper.
 *
 * Lifecycle (same idea as EventBus):
 * 1. Startup, single-threaded: Main and the modules register services (and may
 * read them).
 * 2. Main calls freeze(). Registration is closed and the contents become
 * immutable,
 * so reads are safe from any thread afterwards.
 *
 * Lookup is by the exact registered type: register under the interface you want
 * callers to depend on.
 */
public final class ServiceRegistry {

  private final Map<Class<?>, Object> pending = new LinkedHashMap<>();
  private volatile Map<Class<?>, Object> frozen; // null until freeze()

  /**
   * Register a service under {@code type}. Only allowed before freeze(); each
   * type once.
   */
  public <T> void register(Class<T> type, T instance) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(instance, "instance");
    synchronized (pending) {
      if (frozen != null) {
        throw new IllegalStateException(
            "ServiceRegistry is frozen; cannot register " + type.getSimpleName() + " after startup");
      }
      if (pending.putIfAbsent(type, instance) != null) {
        throw new IllegalStateException(
            "A service is already registered for " + type.getName());
      }
    }
  }

  /**
   * The service registered for {@code type}, or ServiceNotFoundException with a
   * helpful message.
   */
  public <T> T get(Class<T> type) {
    Objects.requireNonNull(type, "type");
    Object service = lookup(type);
    if (service == null) {
      throw new com.lesconstructionssapete.stempyerpserver.platform.module.ServiceNotFoundException(type,
          registeredTypeNames());
    }
    return type.cast(service);
  }

  /**
   * For genuinely optional services. Prefer get(): a missing service is normally
   * a wiring bug.
   */
  public <T> Optional<T> find(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(lookup(type)).map(type::cast);
  }

  /**
   * Close registration. Call once at the end of the services phase. Idempotent.
   */
  public void freeze() {
    synchronized (pending) {
      if (frozen == null) {
        frozen = Map.copyOf(pending);
      }
    }
  }

  private Object lookup(Class<?> type) {
    Map<Class<?>, Object> snapshot = frozen;
    if (snapshot != null) {
      return snapshot.get(type);
    }
    synchronized (pending) {
      return pending.get(type);
    }
  }

  private TreeSet<String> registeredTypeNames() {
    var names = new TreeSet<String>();
    Map<Class<?>, Object> snapshot = frozen;
    if (snapshot != null) {
      snapshot.keySet().forEach(c -> names.add(c.getSimpleName()));
    } else {
      synchronized (pending) {
        pending.keySet().forEach(c -> names.add(c.getSimpleName()));
      }
    }
    return names;
  }

}

package com.lesconstructionssapete.stempyerpserver.platform.events;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxDelivery;

/**
 * One EventBus.subscribeAsync(...) registration: a stable key, the event type
 * it accepts (subtypes included) and its handler, plus optional limits. The
 * key is stored in every outbox row, so never rename it while rows may be
 * pending: they would wait for a handler that no longer exists.
 *
 * Tune it in the same statement as the subscription, before the bus is frozen:
 *
 * <pre>
 * eventBus.subscribeAsync("search.index-entity", EntityChanged.class, handler)
 *     .withMaxAttempts(10)
 *     .withTimeout(Duration.ofSeconds(10));
 * </pre>
 *
 * Unset limits fall back to outbox.defaultMaxAttempts / outbox.defaultTimeout.
 */
public final class AsyncSubscription<E extends DomainEvent> {

  /** Like job keys: "module.what", lower case. */
  private static final Pattern KEY = Pattern.compile("[a-z0-9]+(\\.[a-z0-9-]+)+");
  private static final int MAX_KEY_LENGTH = 100;

  private final String key;
  private final Class<E> type;
  private final AsyncEventHandler<E> handler;
  private volatile Integer maxAttempts;
  private volatile Duration timeout;
  private volatile boolean frozen;

  AsyncSubscription(String key, Class<E> type, AsyncEventHandler<E> handler) {
    Objects.requireNonNull(key, "key");
    if (key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Async subscription key '" + key
          + "' must look like 'module.what' (lower case, digits, dashes; at most 100 characters)");
    }
    this.key = key;
    this.type = Objects.requireNonNull(type, "type");
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  /** At least 1; 1 means "never retry". */
  public AsyncSubscription<E> withMaxAttempts(int attempts) {
    requireNotFrozen();
    if (attempts < 1 || attempts > 100) {
      throw new IllegalArgumentException("maxAttempts must be 1-100");
    }
    this.maxAttempts = attempts;
    return this;
  }

  /** How long one attempt may run before it is interrupted and counted as failed. */
  public AsyncSubscription<E> withTimeout(Duration timeout) {
    requireNotFrozen();
    if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("timeout must be between 100 ms and 1 hour");
    }
    this.timeout = timeout;
    return this;
  }

  public String key() {
    return key;
  }

  public Class<E> type() {
    return type;
  }

  /** Null: outbox.defaultMaxAttempts. */
  public Integer maxAttempts() {
    return maxAttempts;
  }

  /** Null: outbox.defaultTimeout. */
  public Duration timeout() {
    return timeout;
  }

  boolean accepts(DomainEvent event) {
    return type.isInstance(event);
  }

  /** Calls the handler with an event already checked to be an E. */
  public void deliver(OutboxDelivery delivery, DomainEvent event) throws Exception {
    handler.handle(delivery, type.cast(event));
  }

  void freeze() {
    frozen = true;
  }

  private void requireNotFrozen() {
    if (frozen) {
      throw new IllegalStateException("EventBus is frozen; configure subscription " + key + " during startup");
    }
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxWriter;

/**
 * In-process, synchronous, in-transaction event bus.
 *
 * Lifecycle:
 * 1. At startup, each module calls subscribe(...).
 * 2. ModuleHost calls freeze() once all modules are registered.
 * 3. After that the subscriber list is immutable, so publish() needs no locking
 * and is safe to call from any number of request threads.
 *
 * Delivery rules:
 * - Handlers run in registration order, on the publisher's thread, with the
 * publisher's UnitOfWork.
 * - A handler subscribed to a supertype (e.g. DomainEvent.class for auditing)
 * receives every subtype.
 * - If a handler throws, delivery stops and the exception propagates UNCHANGED
 * to the publisher,
 * so the transaction rolls back and your ExceptionMapper still sees the
 * original exception type
 * (e.g. a "period closed" error can still map to a 409).
 * - Handlers may publish further events. A depth limit catches accidental event
 * loops.
 *
 * Async subscriptions (subscribeAsync) are for work that must not break the
 * publisher (emails, webhooks, search indexing). publish() does not run them:
 * after the synchronous handlers, it writes one outbox row per matching async
 * subscription with the publisher's UnitOfWork, so the row commits or rolls
 * back with the business change. The OutboxDispatcher runs the handler later,
 * on another thread, with retries (docs/ARCHITECTURE.md §18).
 */
public class EventBus {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);

  /** Handler chains deeper than this almost certainly mean an event loop. */
  private static final int MAX_DEPTH = 8;

  private record Subscription<E extends DomainEvent>(Class<E> type, EventHandler<E> handler) {
    boolean accepts(DomainEvent event) {
      return type.isInstance(event);
    }

    void deliver(UnitOfWork uow, DomainEvent event) {
      handler.handle(uow, type.cast(event));
    }
  }

  private final OutboxWriter outbox; // null: no async subscriptions allowed
  private final List<Subscription<?>> pending = new ArrayList<>();
  private final Map<String, AsyncSubscription<?>> pendingAsync = new LinkedHashMap<>();
  private volatile List<Subscription<?>> subscriptions; // null until frozen
  private volatile List<AsyncSubscription<?>> asyncSubscriptions; // null until frozen

  // Infrastructure-only bookkeeping for the loop guard; carries no business
  // state.
  private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

  /** A bus for synchronous handlers only: subscribeAsync() is refused. */
  public EventBus() {
    this(null);
  }

  /** @param outbox where publish() writes the events async subscriptions accept */
  public EventBus(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  /** Register a handler. Only allowed before freeze(). */
  public <E extends DomainEvent> void subscribe(Class<E> type, EventHandler<E> handler) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(handler, "handler");
    synchronized (pending) {
      if (subscriptions != null) {
        throw new IllegalStateException(
            "EventBus is frozen; subscribe() must be called during startup");
      }
      pending.add(new Subscription<>(type, handler));
    }
    LOGGER.debug("Subscribed handler to {}", type.getSimpleName());
  }

  /**
   * Register a handler that runs after the publisher's transaction commits,
   * through the outbox. Only allowed before freeze().
   *
   * @param key stable name stored in each outbox row ("module.what"); unique
   * @return the subscription, to set maxAttempts / timeout before freeze()
   */
  public <E extends DomainEvent> AsyncSubscription<E> subscribeAsync(String key, Class<E> type,
      AsyncEventHandler<E> handler) {
    var subscription = new AsyncSubscription<>(key, type, handler);
    synchronized (pending) {
      if (subscriptions != null) {
        throw new IllegalStateException(
            "EventBus is frozen; subscribeAsync() must be called during startup");
      }
      if (outbox == null) {
        throw new IllegalStateException("This EventBus has no outbox; async subscription " + key + " is refused");
      }
      if (pendingAsync.putIfAbsent(key, subscription) != null) {
        throw new IllegalStateException("Async subscription " + key + " is registered twice");
      }
    }
    LOGGER.debug("Subscribed async handler {} to {}", key, type.getSimpleName());
    return subscription;
  }

  /**
   * Call once at the end of startup, after every module has subscribed.
   * Idempotent.
   */
  public void freeze() {
    synchronized (pending) {
      if (subscriptions == null) {
        pendingAsync.values().forEach(AsyncSubscription::freeze);
        asyncSubscriptions = List.copyOf(pendingAsync.values());
        subscriptions = List.copyOf(pending);
        LOGGER.info("EventBus frozen with {} subscription(s) and {} async subscription(s)", subscriptions.size(),
            asyncSubscriptions.size());
      }
    }
  }

  /** The async subscriptions, for the OutboxDispatcher. Only after freeze(). */
  public List<AsyncSubscription<?>> asyncSubscriptions() {
    List<AsyncSubscription<?>> subs = asyncSubscriptions;
    if (subs == null) {
      throw new IllegalStateException("EventBus has not been frozen; call freeze() at the end of startup");
    }
    return subs;
  }

  /**
   * Deliver the event to all matching handlers, inside the caller's transaction,
   * then write an outbox row for each matching async subscription.
   * Publishing an event nobody listens to is fine and does nothing.
   */

  public void publish(UnitOfWork uow, DomainEvent event) {
    Objects.requireNonNull(event, "event");
    List<Subscription<?>> subs = subscriptions;
    if (subs == null) {
      throw new IllegalStateException("EventBus has not been frozen; call freeze() at the end of startup");
    }

    int current = depth.get();
    if (current >= MAX_DEPTH) {
      throw new IllegalStateException("Event handler chain deeper than " + MAX_DEPTH
          + " while publishing " + event.eventType() + "; possible event loop");
    }

    depth.set(current + 1);
    try {
      LOGGER.debug("Publishing {} (company {})", event.eventType(), event.companyId());
      for (Subscription<?> sub : subs) {
        if (sub.accepts(event)) {
          sub.deliver(uow, event);
        }
      }
      for (AsyncSubscription<?> async : asyncSubscriptions) {
        if (async.accepts(event)) {
          outbox.enqueue(uow, async.key(), event);
        }
      }
    } finally {
      if (current == 0) {
        depth.remove();
      } else {
        depth.set(current);
      }
    }
  }

}

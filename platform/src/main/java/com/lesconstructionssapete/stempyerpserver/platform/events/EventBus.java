package com.lesconstructionssapete.stempyerpserver.platform.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * In-process, synchronous, in-transaction event bus.
 *
 * Lifecycle:
 * 1. At startup, each module calls subscribe(...).
 * 2. Main calls freeze() once all modules are registered.
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

  private final List<Subscription<?>> pending = new ArrayList<>();
  private volatile List<Subscription<?>> subscriptions; // null until frozen

  // Infrastructure-only bookkeeping for the loop guard; carries no business
  // state.
  private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

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
   * Call once at the end of startup, after every module has subscribed.
   * Idempotent.
   */
  public void freeze() {
    synchronized (pending) {
      if (subscriptions == null) {
        subscriptions = List.copyOf(pending);
        LOGGER.info("EventBus frozen with {} subscription(s)", subscriptions.size());
      }
    }
  }

  /**
   * Deliver the event to all matching handlers, inside the caller's transaction.
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
    } finally {
      if (current == 0) {
        depth.remove();
      } else {
        depth.set(current);
      }
    }
  }

}

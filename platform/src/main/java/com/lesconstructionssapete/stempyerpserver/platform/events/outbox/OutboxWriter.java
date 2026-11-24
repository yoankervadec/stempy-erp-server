package com.lesconstructionssapete.stempyerpserver.platform.events.outbox;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;

/**
 * Where EventBus puts the events its async subscriptions accept: one outbox
 * row per (event, subscription), written with the publisher's UnitOfWork so it
 * commits or rolls back with the business change.
 */
@FunctionalInterface
public interface OutboxWriter {

  /**
   * @throws RuntimeException if the event can't be stored (e.g. not
   *                          serializable to JSON); the publisher's
   *                          transaction then rolls back
   */
  void enqueue(UnitOfWork uow, String subscriptionKey, DomainEvent event);

}

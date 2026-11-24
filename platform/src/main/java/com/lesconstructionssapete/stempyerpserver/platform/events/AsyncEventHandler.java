package com.lesconstructionssapete.stempyerpserver.platform.events;

import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxDelivery;

/**
 * Reacts to one kind of DomainEvent AFTER the publisher's transaction
 * committed, on an outbox worker thread (see EventBus.subscribeAsync).
 *
 * Unlike EventHandler, it gets no UnitOfWork: the publisher's transaction is
 * long gone. Open your own with UnitOfWorkFactory if you need the database,
 * acting as {@code delivery.requestContext()}.
 *
 * Throwing (or running past the subscription's timeout) means "try again
 * later"; after the last attempt the message is DEAD. Delivery is at least
 * once, so a handler may see the same message twice (a crash after it ran but
 * before it was recorded): make it idempotent, e.g. by passing
 * {@code delivery.messageId()} as an idempotency key to the remote system.
 * Long loops should stop when the thread is interrupted (timeout, shutdown).
 */
@FunctionalInterface
public interface AsyncEventHandler<E extends DomainEvent> {

  void handle(OutboxDelivery delivery, E event) throws Exception;

}

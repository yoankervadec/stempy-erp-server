package com.lesconstructionssapete.stempyerpserver.platform.events;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * Reacts to one kind of DomainEvent.
 *
 * Runs synchronously inside the publisher's transaction, using the same
 * UnitOfWork:
 * if the handler throws, the publisher's whole transaction rolls back. That is
 * exactly what you want for GL postings, and exactly what you do NOT want for
 * sending an email (use the outbox for that, later).
 *
 * Handlers should not depend on running before or after another handler.
 */
@FunctionalInterface
public interface EventHandler<E extends DomainEvent> {

  void handle(UnitOfWork uow, E event);

}

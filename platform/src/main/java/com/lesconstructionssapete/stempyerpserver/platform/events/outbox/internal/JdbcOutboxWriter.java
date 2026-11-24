package com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxWriter;

/**
 * Writes the outbox row for one (event, async subscription): the event as JSON
 * (JsonMapperFactory settings, the same mapper the dispatcher reads it back
 * with) and its class name.
 */
public final class JdbcOutboxWriter implements OutboxWriter {

  private final ObjectMapper json;
  private final OutboxRepository outbox = new OutboxRepository();

  public JdbcOutboxWriter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public void enqueue(UnitOfWork uow, String subscriptionKey, DomainEvent event) {
    String payload;
    try {
      payload = json.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      // A programming error (an event that isn't a plain record): fail the publisher loudly.
      throw new IllegalStateException("Event " + event.getClass().getName() + " for async subscription "
          + subscriptionKey + " cannot be written as JSON", e);
    }
    outbox.insert(uow, subscriptionKey, event.getClass().getName(), payload, event.companyId());
  }
}

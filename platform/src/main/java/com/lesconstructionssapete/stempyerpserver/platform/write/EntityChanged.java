package com.lesconstructionssapete.stempyerpserver.platform.write;

import java.time.Instant;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;

/**
 * Published, in the writing transaction, by every generic create / update /
 * delete (EntityEndpoints). Subscribe to EntityChanged for all of them, e.g.
 * an audit log; a listener that throws rolls the write back.
 *
 * Carries ids and field names, not values: a listener that needs the row
 * reads it with the same UnitOfWork.
 */
public sealed interface EntityChanged extends DomainEvent {

  /** EntityDefinition.name(), e.g. "product". */
  String entity();

  long id();

  long userId();

  Instant occurredAt();

  record Created(String entity, long id, long companyId, long userId, Instant occurredAt)
      implements EntityChanged {
  }

  /** @param fields the API names of the fields the client changed */
  record Updated(String entity, long id, long companyId, long userId, Instant occurredAt, Set<String> fields)
      implements EntityChanged {
    public Updated {
      fields = Set.copyOf(fields);
    }
  }

  /** Soft-deleted. */
  record Deleted(String entity, long id, long companyId, long userId, Instant occurredAt)
      implements EntityChanged {
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.write;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;

/**
 * Published, in the writing transaction, by every generic create / update /
 * delete (EntityEndpoints). Subscribe to EntityChanged for all of them, e.g.
 * the audit trail; a listener that throws rolls the write back.
 *
 * Carries the values that changed, by API field name: for Created, every
 * field the client set (old value null); for Updated, only the fields whose
 * value actually changed, old value read under the row lock. Internal fields
 * never appear (clients can't write them). Values are the Java values of the
 * fields (BigDecimal, Instant, enums...); after an outbox round trip they are
 * whatever JSON gives back (strings for decimals and dates).
 */
public sealed interface EntityChanged extends DomainEvent {

  /** EntityDefinition.name(), e.g. "product". */
  String entity();

  long id();

  long userId();

  /** RequestContext.requestId of the write: ties listeners' work (an audit entry) to the request's logs. */
  String requestId();

  Instant occurredAt();

  /** Field name -> its old and new value; empty when nothing a client sees changed (e.g. a delete). */
  Map<String, FieldChange> changes();

  /** One field's value before and after; {@code oldValue} null for a creation. */
  record FieldChange(Object oldValue, Object newValue) {
  }

  /** @param changes every field the client set, old value null */
  record Created(String entity, long id, long companyId, long userId, String requestId, Instant occurredAt,
      Map<String, FieldChange> changes) implements EntityChanged {
    public Created {
      changes = copy(changes);
    }
  }

  /**
   * @param fields  the API names of the fields the client sent (changed or
   *                not)
   * @param changes the fields whose value changed; may be empty (a PATCH
   *                that repeats the current values)
   */
  record Updated(String entity, long id, long companyId, long userId, String requestId, Instant occurredAt,
      Set<String> fields, Map<String, FieldChange> changes) implements EntityChanged {
    public Updated {
      fields = Set.copyOf(fields);
      changes = copy(changes);
    }
  }

  /** Soft-deleted. */
  record Deleted(String entity, long id, long companyId, long userId, String requestId, Instant occurredAt)
      implements EntityChanged {
    @Override
    public Map<String, FieldChange> changes() {
      return Map.of();
    }
  }

  /** Keeps field order and allows null values (Map.copyOf would refuse them). */
  private static Map<String, FieldChange> copy(Map<String, FieldChange> changes) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(changes));
  }
}

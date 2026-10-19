package com.lesconstructionssapete.stempyerpserver.platform.events;

/**
 * Marker for "something business-relevant happened".
 *
 * Name events in the past tense (SalesOrderPostedEvent, JournalPostedEvent) and
 * make them immutable: a record is ideal. Carry IDs and the facts listeners
 * need, not live entities, so a listener can't accidentally mutate the
 * publisher's objects.
 *
 * Each module's public events live in its own events/ package. That package is
 * the only part of a module other modules are allowed to import (enforced later
 * with ArchUnit).
 */
public interface DomainEvent {

  long companyId();

  default String eventType() {
    return getClass().getSimpleName();
  }

}

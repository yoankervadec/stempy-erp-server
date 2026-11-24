package com.lesconstructionssapete.stempyerpserver.platform.audit;

import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventHandler;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged;

/**
 * Records every generic create / update / delete (EntityChanged) in the audit
 * log, synchronously, in the writing transaction. Subscribed by ModuleHost
 * before any module's handlers.
 *
 * An update whose values did not change (a PATCH repeating the current
 * values) is not recorded. An install-wide entity (no company column) is
 * recorded with no company.
 */
public final class AuditTrail implements EventHandler<EntityChanged> {

  private final AuditLog log;
  private final EntityRegistry entities;

  public AuditTrail(AuditLog log, EntityRegistry entities) {
    this.log = Objects.requireNonNull(log, "log");
    this.entities = Objects.requireNonNull(entities, "entities");
  }

  @Override
  public void handle(UnitOfWork uow, EntityChanged event) {
    String action = switch (event) {
      case EntityChanged.Created _ -> AuditEntry.CREATED;
      case EntityChanged.Updated _ -> AuditEntry.UPDATED;
      case EntityChanged.Deleted _ -> AuditEntry.DELETED;
    };
    if (event instanceof EntityChanged.Updated && event.changes().isEmpty()) {
      return;
    }
    boolean perCompany = entities.find(event.entity()).map(EntityDefinition::companyColumn).isPresent();
    log.record(uow, new AuditEntry(event.entity(), event.id(), perCompany ? event.companyId() : null, action,
        event.userId(), event.requestId(), event.occurredAt(), event.changes()));
  }
}

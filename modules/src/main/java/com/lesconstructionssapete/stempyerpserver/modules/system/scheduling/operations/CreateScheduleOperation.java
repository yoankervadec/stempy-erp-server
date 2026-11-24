package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleFields;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Inserts a validated schedule; returns its id. Names are unique per company
 * (and among system schedules). Audited as CREATED (entity "schedule") with
 * every field set.
 */
public class CreateScheduleOperation implements Operation<CreateScheduleOperation.Input, Long> {

  /** @param companyId null: an install-wide schedule */
  public record Input(Long companyId, ScheduleFields fields, long userId, String requestId, Instant now) {
  }

  private final ScheduleAdminRepository repository;
  private final AuditLog audit;

  public CreateScheduleOperation(ScheduleAdminRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    if (repository.nameTaken(uow, in.companyId(), in.fields().name(), 0)) {
      throw ValidationFailedException.field("name", "ALREADY_EXISTS", "another schedule already has this name");
    }
    long id = repository.insertSchedule(uow, in.companyId(), in.fields(), in.userId(), in.now());
    audit.record(uow, new AuditEntry("schedule", id, in.companyId(), AuditEntry.CREATED, in.userId(), in.requestId(),
        in.now(), ScheduleFields.changes(null, in.fields())));
    return id;
  }
}

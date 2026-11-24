package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleFields;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredSchedule;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Saves validated changes if nobody changed the schedule since {@code version}
 * was read. Audited as UPDATED (entity "schedule") with the fields that
 * changed; nothing is recorded if none did. {@code current} is the version the
 * update is guarded by, so it is exactly what the row held before.
 */
public class UpdateScheduleOperation implements Operation<UpdateScheduleOperation.Input, Void> {

  public record Input(StoredSchedule current, int version, ScheduleFields fields, long userId, String requestId,
      Instant now) {
  }

  private final ScheduleAdminRepository repository;
  private final AuditLog audit;

  public UpdateScheduleOperation(ScheduleAdminRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    StoredSchedule s = in.current();
    if (!s.fields().name().equals(in.fields().name())
        && repository.nameTaken(uow, s.companyId(), in.fields().name(), s.id())) {
      throw ValidationFailedException.field("name", "ALREADY_EXISTS", "another schedule already has this name");
    }
    if (repository.updateSchedule(uow, s.id(), in.version(), in.fields(), in.userId(), in.now()) == 0) {
      throw versionConflict();
    }
    var changes = ScheduleFields.changes(s.fields(), in.fields());
    if (!changes.isEmpty()) {
      audit.record(uow, new AuditEntry("schedule", s.id(), s.companyId(), AuditEntry.UPDATED, in.userId(),
          in.requestId(), in.now(), changes));
    }
    return null;
  }

  private static ConflictException versionConflict() {
    return new ConflictException("VERSION_CONFLICT", "The schedule was changed by someone else; reload and retry");
  }
}

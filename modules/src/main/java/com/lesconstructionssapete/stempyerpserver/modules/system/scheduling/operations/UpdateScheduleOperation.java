package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleFields;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredSchedule;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/** Saves validated changes if nobody changed the schedule since {@code version} was read. */
public class UpdateScheduleOperation implements Operation<UpdateScheduleOperation.Input, Void> {

  public record Input(StoredSchedule current, int version, ScheduleFields fields, long userId, Instant now) {
  }

  private final ScheduleAdminRepository repository;

  public UpdateScheduleOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
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
    return null;
  }

  private static ConflictException versionConflict() {
    return new ConflictException("VERSION_CONFLICT", "The schedule was changed by someone else; reload and retry");
  }
}

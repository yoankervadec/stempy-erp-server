package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredSchedule;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;

/**
 * Soft-deletes a schedule (its run history stays) and ends its queued manual
 * runs as SKIPPED. A run already in progress finishes normally.
 */
public class DeleteScheduleOperation implements Operation<DeleteScheduleOperation.Input, Void> {

  public record Input(StoredSchedule current, int version, long userId, Instant now) {
  }

  private final ScheduleAdminRepository repository;

  public DeleteScheduleOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    if (repository.softDeleteSchedule(uow, in.current().id(), in.version(), in.userId(), in.now()) == 0) {
      throw new ConflictException("VERSION_CONFLICT", "The schedule was changed by someone else; reload and retry");
    }
    repository.skipQueuedRuns(uow, in.current().id(), in.now(), "Schedule deleted before the run started");
    return null;
  }
}

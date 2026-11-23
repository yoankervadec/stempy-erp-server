package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredSchedule;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;

/**
 * Queues a manual run acting as {@code userId}; returns the run id. One
 * queued run per schedule at a time, so a double click doesn't run it twice.
 */
public class QueueManualRunOperation implements Operation<QueueManualRunOperation.Input, Long> {

  /** @param parameters JSON text overriding the schedule's, or null */
  public record Input(StoredSchedule schedule, long userId, String parameters, Instant now) {
  }

  private final ScheduleAdminRepository repository;

  public QueueManualRunOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    if (repository.hasQueuedRun(uow, in.schedule().id())) {
      throw new ConflictException("RUN_ALREADY_QUEUED",
          "A manual run of this schedule is already waiting to start");
    }
    return repository.insertManualRun(uow, in.schedule(), in.userId(), in.parameters(), in.now());
  }
}

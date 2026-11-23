package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleFields;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/** Inserts a validated schedule; returns its id. Names are unique per company (and among system schedules). */
public class CreateScheduleOperation implements Operation<CreateScheduleOperation.Input, Long> {

  /** @param companyId null: an install-wide schedule */
  public record Input(Long companyId, ScheduleFields fields, long userId, Instant now) {
  }

  private final ScheduleAdminRepository repository;

  public CreateScheduleOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    if (repository.nameTaken(uow, in.companyId(), in.fields().name(), 0)) {
      throw ValidationFailedException.field("name", "ALREADY_EXISTS", "another schedule already has this name");
    }
    return repository.insertSchedule(uow, in.companyId(), in.fields(), in.userId(), in.now());
  }
}

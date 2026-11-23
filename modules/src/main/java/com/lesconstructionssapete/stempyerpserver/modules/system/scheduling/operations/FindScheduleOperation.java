package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredSchedule;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** One live schedule, whatever its scope: the caller decides who may see it. */
public class FindScheduleOperation implements Operation<Long, Optional<StoredSchedule>> {

  private final ScheduleAdminRepository repository;

  public FindScheduleOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredSchedule> execute(UnitOfWork uow, Long id) {
    return repository.findSchedule(uow, id);
  }
}

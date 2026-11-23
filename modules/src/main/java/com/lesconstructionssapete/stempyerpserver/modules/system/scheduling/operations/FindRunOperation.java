package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredRun;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** One run, whatever its scope: the caller decides who may see it. */
public class FindRunOperation implements Operation<Long, Optional<StoredRun>> {

  private final ScheduleAdminRepository repository;

  public FindRunOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredRun> execute(UnitOfWork uow, Long id) {
    return repository.findRun(uow, id);
  }
}

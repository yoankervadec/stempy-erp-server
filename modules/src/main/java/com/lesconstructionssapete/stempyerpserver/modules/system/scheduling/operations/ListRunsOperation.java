package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredRun;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;

/** Run history, newest first. */
public class ListRunsOperation implements Operation<ListRunsOperation.Input, List<StoredRun>> {

  /** @param scheduleId null: all; @param status null: all */
  public record Input(Long companyId, boolean system, Long scheduleId, RunStatus status, int limit, int offset) {
  }

  private final ScheduleAdminRepository repository;

  public ListRunsOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<StoredRun> execute(UnitOfWork uow, Input in) {
    return repository.listRuns(uow, in.companyId(), in.system(), in.scheduleId(), in.status(), in.limit(),
        in.offset());
  }
}

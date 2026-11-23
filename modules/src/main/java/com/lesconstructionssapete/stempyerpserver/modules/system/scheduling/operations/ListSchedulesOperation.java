package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.StoredSchedule;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Schedules of a company and/or the install-wide ones, by name. */
public class ListSchedulesOperation implements Operation<ListSchedulesOperation.Input, List<StoredSchedule>> {

  /** @param companyId null: no company rows; @param system include install-wide rows */
  public record Input(Long companyId, boolean system, int limit, int offset) {
  }

  private final ScheduleAdminRepository repository;

  public ListSchedulesOperation(ScheduleAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<StoredSchedule> execute(UnitOfWork uow, Input in) {
    return repository.listSchedules(uow, in.companyId(), in.system(), in.limit(), in.offset());
  }
}

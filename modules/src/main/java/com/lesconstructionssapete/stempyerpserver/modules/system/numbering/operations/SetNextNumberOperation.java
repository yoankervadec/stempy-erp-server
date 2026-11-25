package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations;

import java.time.Instant;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberSeriesAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.SeriesFields;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Moves a counter so its next number is {@code nextNumber}, creating it if
 * needed: to continue an old system's numbering. A counter only moves up
 * ({@code COUNTER_CANNOT_DECREASE}, 409): numbers already issued would come
 * back. The same value is accepted and changes nothing.
 *
 * Waits for an open GAPLESS transaction holding the counter. Audited as
 * COUNTER_SET (entity "numberSeries") with {@code counters[scope].nextNumber}.
 * Returns whether anything changed.
 */
public class SetNextNumberOperation implements Operation<SetNextNumberOperation.Input, Boolean> {

  public static final String ACTION = "COUNTER_SET";
  public static final String CANNOT_DECREASE = "COUNTER_CANNOT_DECREASE";

  public record Input(long seriesId, long companyId, String scope, long nextNumber, long userId, String requestId,
      Instant now) {
  }

  private final NumberSeriesAdminRepository repository;
  private final AuditLog audit;

  public SetNextNumberOperation(NumberSeriesAdminRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Boolean execute(UnitOfWork uow, Input in) {
    long target = in.nextNumber() - 1;
    Long oldNext = null;
    if (!repository.insertCounterIfAbsent(uow, in.seriesId(), in.scope(), target)) {
      long last = repository.lastNumberForUpdate(uow, in.seriesId(), in.scope());
      if (target < last) {
        throw new ConflictException(CANNOT_DECREASE, "The next number is already " + (last + 1)
            + "; a counter can only move up, or numbers already issued would come back");
      }
      if (target == last) {
        return false;
      }
      repository.setLastNumber(uow, in.seriesId(), in.scope(), target);
      oldNext = last + 1;
    }
    audit.record(uow, new AuditEntry(SeriesFields.AUDIT_ENTITY, in.seriesId(), in.companyId(), ACTION,
        in.userId(), in.requestId(), in.now(),
        Map.of("counters[" + in.scope() + "].nextNumber", new FieldChange(oldNext, in.nextNumber()))));
    return true;
  }
}

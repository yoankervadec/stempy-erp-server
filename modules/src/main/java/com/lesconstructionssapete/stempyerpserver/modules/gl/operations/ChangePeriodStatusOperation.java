package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.Instant;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodStatus;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredPeriod;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Closes or reopens a period. The row is locked first (FOR UPDATE): a close
 * waits for entries being posted into the period (they hold it FOR SHARE).
 * Already in that state: {@code PERIOD_ALREADY_CLOSED} / {@code PERIOD_ALREADY_OPEN}
 * (409). Audited as CLOSED / REOPENED (entity "glPeriod").
 */
public class ChangePeriodStatusOperation implements Operation<ChangePeriodStatusOperation.Input, Void> {

  public static final String AUDIT_ENTITY = "glPeriod";
  public static final String CLOSED = "CLOSED";
  public static final String REOPENED = "REOPENED";

  public record Input(long companyId, long periodId, PeriodStatus target, long userId, String requestId,
      Instant now) {
  }

  private final PeriodRepository repository;
  private final AuditLog audit;

  public ChangePeriodStatusOperation(PeriodRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    StoredPeriod p = repository.findPeriodForUpdate(uow, in.companyId(), in.periodId())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Period " + in.periodId() + " not found"));
    if (p.status() == in.target()) {
      throw new ConflictException(in.target() == PeriodStatus.CLOSED ? "PERIOD_ALREADY_CLOSED"
          : "PERIOD_ALREADY_OPEN", "The period is already " + in.target().name().toLowerCase(java.util.Locale.ROOT));
    }
    repository.setStatus(uow, p.companyId(), p.id(), in.target(), in.userId(), in.now());
    audit.record(uow, new AuditEntry(AUDIT_ENTITY, p.id(), p.companyId(),
        in.target() == PeriodStatus.CLOSED ? CLOSED : REOPENED, in.userId(), in.requestId(), in.now(),
        Map.of("status", new FieldChange(p.status().name(), in.target().name()))));
    return null;
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredFiscalYear;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Creates a fiscal year starting on {@code startDate} (the 1st of a month,
 * checked by the orchestrator) and its 12 monthly periods, all OPEN. The
 * company row is locked first, so years are created one at a time.
 *
 * The company's first year may start in any month; every later one must
 * start the day after the latest one ends ({@code NOT_CONTIGUOUS}). Audited as
 * CREATED (entity "glFiscalYear"). Returns the year's id.
 */
public class CreateFiscalYearOperation implements Operation<CreateFiscalYearOperation.Input, Long> {

  public static final String AUDIT_ENTITY = "glFiscalYear";
  public static final int PERIODS = 12;

  public record Input(long companyId, LocalDate startDate, long userId, String requestId, Instant now) {
  }

  private final PeriodRepository repository;
  private final AuditLog audit;

  public CreateFiscalYearOperation(PeriodRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    if (!repository.lockCompany(uow, in.companyId())) {
      throw new IllegalStateException("company " + in.companyId() + " not found");
    }
    var last = repository.lastFiscalYear(uow, in.companyId());
    if (last.isPresent()) {
      LocalDate expected = last.map(StoredFiscalYear::endDate).get().plusDays(1);
      if (!in.startDate().equals(expected)) {
        throw ValidationFailedException.field("startDate", "NOT_CONTIGUOUS",
            "the next fiscal year must start on " + expected + ", the day after the last one ends");
      }
    }
    LocalDate end = in.startDate().plusMonths(PERIODS).minusDays(1);
    long yearId = repository.insertFiscalYear(uow, in.companyId(), in.startDate(), end, in.userId(), in.now());
    for (int n = 1; n <= PERIODS; n++) {
      LocalDate start = in.startDate().plusMonths(n - 1L);
      repository.insertPeriod(uow, in.companyId(), yearId, n, start, start.plusMonths(1).minusDays(1));
    }
    Map<String, FieldChange> changes = new LinkedHashMap<>();
    changes.put("startDate", new FieldChange(null, in.startDate().toString()));
    changes.put("endDate", new FieldChange(null, end.toString()));
    audit.record(uow, new AuditEntry(AUDIT_ENTITY, yearId, in.companyId(), AuditEntry.CREATED, in.userId(),
        in.requestId(), in.now(), changes));
    return yearId;
  }
}

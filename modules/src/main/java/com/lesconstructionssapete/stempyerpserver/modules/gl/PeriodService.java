package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.dto.FiscalYearView;
import com.lesconstructionssapete.stempyerpserver.modules.gl.dto.PeriodView;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ChangePeriodStatusOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.CreateFiscalYearOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindPeriodOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListPeriodsOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditHistory;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditRecord;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Fiscal years and periods (orchestrator), docs/ARCHITECTURE.md §22.4. The
 * request's company only; another company's periods are 404.
 *
 * <ul>
 * <li>read: gl.period.read;</li>
 * <li>create a year, close a period: gl.period.manage;</li>
 * <li>reopen a period: gl.period.reopen (on its own: closing and reopening are
 * separate decisions).</li>
 * </ul>
 */
public class PeriodService {

  public static final String READ = "gl.period.read";
  public static final String MANAGE = "gl.period.manage";
  public static final String REOPEN = "gl.period.reopen";

  private final UnitOfWorkFactory uowFactory;
  private final PermissionChecker permissions;
  private final Clock clock;
  private final ListPeriodsOperation listPeriods;
  private final FindPeriodOperation findPeriod;
  private final CreateFiscalYearOperation createFiscalYear;
  private final ChangePeriodStatusOperation changeStatus;
  private final AuditLog audit;
  private final Optional<UserDirectory> userDirectory;

  public PeriodService(UnitOfWorkFactory uowFactory, PermissionChecker permissions, Clock clock,
      ListPeriodsOperation listPeriods, FindPeriodOperation findPeriod, CreateFiscalYearOperation createFiscalYear,
      ChangePeriodStatusOperation changeStatus, AuditLog audit, Optional<UserDirectory> userDirectory) {
    this.uowFactory = uowFactory;
    this.permissions = permissions;
    this.clock = clock;
    this.listPeriods = listPeriods;
    this.findPeriod = findPeriod;
    this.createFiscalYear = createFiscalYear;
    this.changeStatus = changeStatus;
    this.audit = audit;
    this.userDirectory = userDirectory;
  }

  /** The company's fiscal years, oldest first, each with its periods. */
  public List<FiscalYearView> fiscalYears(RequestContext rc) {
    permissions.require(rc, READ);
    var all = uowFactory.executeReadOnly(uow -> listPeriods.execute(uow, rc.companyId()));
    Map<Long, List<PeriodView>> byYear = all.periods().stream()
        .collect(Collectors.groupingBy(StoredPeriod::fiscalYearId, Collectors.mapping(PeriodService::view,
            Collectors.toList())));
    return all.years().stream().map(y -> view(y, byYear.getOrDefault(y.id(), List.of()))).toList();
  }

  /** {"startDate": "2026-04-01"}: the 1st of a month. Returns the new year with its periods. */
  public FiscalYearView createFiscalYear(RequestContext rc, JsonNode body) {
    permissions.require(rc, MANAGE);
    LocalDate start = startDate(body);
    Instant now = clock.instant();
    long id = uowFactory.execute(uow -> createFiscalYear.execute(uow, new CreateFiscalYearOperation.Input(
        rc.companyId(), start, rc.userId(), rc.requestId(), now)));
    return fiscalYears(rc).stream().filter(y -> y.id() == id).findFirst()
        .orElseThrow(() -> new IllegalStateException("fiscal year " + id + " not found after creating it"));
  }

  public PeriodView getPeriod(RequestContext rc, long id) {
    permissions.require(rc, READ);
    return view(visiblePeriod(rc, id));
  }

  public PeriodView closePeriod(RequestContext rc, long id) {
    permissions.require(rc, MANAGE);
    return changeStatus(rc, id, PeriodStatus.CLOSED);
  }

  public PeriodView reopenPeriod(RequestContext rc, long id) {
    permissions.require(rc, REOPEN);
    return changeStatus(rc, id, PeriodStatus.OPEN);
  }

  /** The period's closes and reopens, newest first. Needs audit.history.read on top of read. */
  public List<Map<String, Object>> periodHistory(RequestContext rc, long id, int limit, int offset) {
    permissions.require(rc, READ);
    StoredPeriod p = visiblePeriod(rc, id);
    permissions.require(rc, AuditLog.READ_PERMISSION);
    return uowFactory.executeReadOnly(uow -> {
      List<AuditRecord> records = audit.history(uow, ChangePeriodStatusOperation.AUDIT_ENTITY, id, p.companyId(),
          limit, offset);
      Set<Long> userIds = records.stream().map(AuditRecord::userId).filter(Objects::nonNull)
          .collect(Collectors.toSet());
      Map<Long, UserDirectory.UserSummary> users = userIds.isEmpty() ? Map.of()
          : userDirectory.map(d -> d.find(uow, userIds)).orElse(Map.of());
      return AuditHistory.view(records, users);
    });
  }

  // ---- rules ----

  private PeriodView changeStatus(RequestContext rc, long id, PeriodStatus target) {
    visiblePeriod(rc, id);
    Instant now = clock.instant();
    uowFactory.run(uow -> changeStatus.execute(uow, new ChangePeriodStatusOperation.Input(id, target, rc.userId(),
        rc.requestId(), now)));
    return view(visiblePeriod(rc, id));
  }

  private StoredPeriod visiblePeriod(RequestContext rc, long id) {
    return uowFactory.executeReadOnly(uow -> findPeriod.execute(uow, id))
        .filter(p -> p.companyId() == rc.companyId())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Period " + id + " not found"));
  }

  private static LocalDate startDate(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
    var v = Validation.check();
    LocalDate start = null;
    for (Iterator<String> it = body.fieldNames(); it.hasNext();) {
      String f = it.next();
      if (!f.equals("startDate")) {
        v.add(f, "UNKNOWN_FIELD", "unknown field '" + f + "'");
      }
    }
    JsonNode n = body.get("startDate");
    if (n == null || n.isNull()) {
      v.add("startDate", "REQUIRED", "is required");
    } else {
      try {
        start = LocalDate.parse(n.asText());
        if (start.getDayOfMonth() != 1) {
          v.add("startDate", "NOT_FIRST_OF_MONTH", "a fiscal year starts on the 1st of a month");
        } else if (start.getYear() < 1900 || start.getYear() > 9000) {
          v.add("startDate", "OUT_OF_RANGE", "must be between 1900 and 9000");
        }
      } catch (DateTimeParseException e) {
        v.add("startDate", "INVALID_FORMAT", "must be a date such as 2026-04-01");
      }
    }
    v.throwIfInvalid();
    return start;
  }

  // ---- views ----

  private static PeriodView view(StoredPeriod p) {
    return new PeriodView(p.id(), p.fiscalYearId(), p.periodNo(), p.startDate().toString().substring(0, 7),
        p.startDate(), p.endDate(), p.status(), p.statusChangedAt(), p.statusChangedBy());
  }

  private static FiscalYearView view(StoredFiscalYear y, List<PeriodView> periods) {
    String name = y.startDate().getMonthValue() == 1 ? String.valueOf(y.startDate().getYear())
        : y.startDate().getYear() + "-" + y.endDate().getYear();
    return new FiscalYearView(y.id(), name, y.startDate(), y.endDate(), y.createdAt(), y.createdBy(),
        new ArrayList<>(periods));
  }
}

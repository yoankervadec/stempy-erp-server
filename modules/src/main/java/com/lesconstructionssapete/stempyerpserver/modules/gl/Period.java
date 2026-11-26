package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Instant;
import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;

/**
 * A fiscal period, for the generic reads (docs/ARCHITECTURE.md §22.4):
 * closed and reopened through PeriodController's actions, never written
 * generically.
 */
public final class Period {

  /** The audit entries' name too (ChangePeriodStatusOperation.AUDIT_ENTITY). */
  public static final EntityDefinition<Period> DEF = EntityDefinition.of("glPeriod", "gl_periods", Period::new);

  public static final Field<Period, Long> ID = DEF.id("id", p -> p.id, (p, v) -> p.id = v);
  public static final Field<Period, Long> COMPANY_ID = DEF.companyId(p -> p.companyId, (p, v) -> p.companyId = v);
  public static final Field<Period, Long> FISCAL_YEAR_ID = DEF.integer("fiscalYearId", "fiscal_year_id",
      p -> p.fiscalYearId, (p, v) -> p.fiscalYearId = v).readOnly().filterable();
  public static final Field<Period, Long> PERIOD_NO = DEF.integer("periodNo", "period_no", p -> p.periodNo,
      (p, v) -> p.periodNo = v).readOnly().filterable().sortable();
  public static final Field<Period, LocalDate> START_DATE = DEF.date("startDate", "start_date", p -> p.startDate,
      (p, v) -> p.startDate = v).readOnly().filterable().sortable();
  public static final Field<Period, LocalDate> END_DATE = DEF.date("endDate", "end_date", p -> p.endDate,
      (p, v) -> p.endDate = v).readOnly().filterable().sortable();
  public static final Field<Period, PeriodStatus> STATUS = DEF.enumeration("status", "status", PeriodStatus.class,
      p -> p.status, (p, v) -> p.status = v).readOnly().filterable();
  public static final Field<Period, Long> VERSION = DEF.version("version", p -> p.version, (p, v) -> p.version = v);
  public static final Field<Period, Instant> STATUS_CHANGED_AT = DEF.instant("statusChangedAt",
      "status_changed_at", p -> p.statusChangedAt, (p, v) -> p.statusChangedAt = v).readOnly();
  public static final Field<Period, Long> STATUS_CHANGED_BY = DEF.integer("statusChangedBy", "status_changed_by",
      p -> p.statusChangedBy, (p, v) -> p.statusChangedBy = v).readOnly();

  public static final Relation<Period, FiscalYear> FISCAL_YEAR_REL = DEF.manyToOne("fiscalYear",
      () -> FiscalYear.DEF, "fiscal_year_id");

  static {
    DEF.titleField(START_DATE).subtitleField(END_DATE)
        .listColumns(START_DATE, END_DATE, PERIOD_NO, STATUS, FISCAL_YEAR_ID)
        .defaultSort(SortKey.asc(START_DATE));
  }

  private Long id;
  private Long companyId;
  private Long fiscalYearId;
  private Long periodNo;
  private LocalDate startDate;
  private LocalDate endDate;
  private PeriodStatus status;
  private Long version;
  private Instant statusChangedAt;
  private Long statusChangedBy;
}

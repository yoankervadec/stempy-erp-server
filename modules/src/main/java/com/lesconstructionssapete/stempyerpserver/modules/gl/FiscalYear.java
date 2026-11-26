package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Instant;
import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;

/**
 * A fiscal year, for the generic reads and create form (docs/ARCHITECTURE.md
 * §22.4): only its start date is given when creating one (POST
 * /gl/fiscal-years, PeriodService), the rest is the server's.
 */
public final class FiscalYear {

  /** The audit entries' name too (CreateFiscalYearOperation.AUDIT_ENTITY). */
  public static final EntityDefinition<FiscalYear> DEF = EntityDefinition.of("glFiscalYear", "gl_fiscal_years",
      FiscalYear::new);

  public static final Field<FiscalYear, Long> ID = DEF.id("id", y -> y.id, (y, v) -> y.id = v);
  public static final Field<FiscalYear, Long> COMPANY_ID = DEF.companyId(y -> y.companyId,
      (y, v) -> y.companyId = v);
  public static final Field<FiscalYear, LocalDate> START_DATE = DEF.date("startDate", "start_date",
      y -> y.startDate, (y, v) -> y.startDate = v).required().immutable().filterable().sortable();
  public static final Field<FiscalYear, LocalDate> END_DATE = DEF.date("endDate", "end_date", y -> y.endDate,
      (y, v) -> y.endDate = v).readOnly().filterable().sortable();
  public static final Field<FiscalYear, Instant> CREATED_AT = DEF.instant("createdAt", "created_at",
      y -> y.createdAt, (y, v) -> y.createdAt = v).readOnly();
  public static final Field<FiscalYear, Long> CREATED_BY = DEF.integer("createdBy", "created_by", y -> y.createdBy,
      (y, v) -> y.createdBy = v).readOnly();

  /** The year's twelve periods (its page's table). */
  public static final Relation<FiscalYear, Period> PERIODS_REL = DEF.oneToMany("periods", () -> Period.DEF,
      "fiscal_year_id");

  static {
    DEF.titleField(START_DATE).subtitleField(END_DATE).defaultSort(SortKey.desc(START_DATE));
  }

  private Long id;
  private Long companyId;
  private LocalDate startDate;
  private LocalDate endDate;
  private Instant createdAt;
  private Long createdBy;
}

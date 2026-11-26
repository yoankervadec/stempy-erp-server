package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;

/**
 * A posted journal entry, as the generic reads see it (docs/ARCHITECTURE.md
 * §22.5, §23.3): the QUERY list, _refs and _meta. Every field is read-only:
 * entries are posted and reversed through GeneralLedgerApi /
 * JournalController, never written generically. Named like its audit entries.
 */
public final class JournalEntry {

  public static final EntityDefinition<JournalEntry> DEF = EntityDefinition.of(StoredEntry.AUDIT_ENTITY,
      "gl_journal_entries", JournalEntry::new);

  public static final Field<JournalEntry, Long> ID = DEF.id("id", e -> e.id, (e, v) -> e.id = v);
  public static final Field<JournalEntry, Long> COMPANY_ID = DEF.companyId(e -> e.companyId,
      (e, v) -> e.companyId = v);
  public static final Field<JournalEntry, String> ENTRY_NO = DEF.string("entryNo", "entry_no", e -> e.entryNo,
      (e, v) -> e.entryNo = v).readOnly().unique().filterable().sortable();
  public static final Field<JournalEntry, LocalDate> POSTING_DATE = DEF.date("postingDate", "posting_date",
      e -> e.postingDate, (e, v) -> e.postingDate = v).readOnly().filterable().sortable();
  public static final Field<JournalEntry, Long> PERIOD_ID = DEF.integer("periodId", "period_id", e -> e.periodId,
      (e, v) -> e.periodId = v).readOnly().filterable();
  public static final Field<JournalEntry, String> DESCRIPTION = DEF.string("description", "description",
      e -> e.description, (e, v) -> e.description = v).readOnly().filterable();
  public static final Field<JournalEntry, String> SOURCE_TYPE = DEF.string("sourceType", "source_type",
      e -> e.sourceType, (e, v) -> e.sourceType = v).readOnly().filterable().sortable();
  public static final Field<JournalEntry, Long> SOURCE_ID = DEF.integer("sourceId", "source_id", e -> e.sourceId,
      (e, v) -> e.sourceId = v).readOnly().filterable();
  public static final Field<JournalEntry, String> SOURCE_NO = DEF.string("sourceNo", "source_no", e -> e.sourceNo,
      (e, v) -> e.sourceNo = v).readOnly().filterable();
  public static final Field<JournalEntry, Long> REVERSAL_OF_ID = DEF.integer("reversalOfId", "reversal_of_id",
      e -> e.reversalOfId, (e, v) -> e.reversalOfId = v).readOnly().filterable();
  public static final Field<JournalEntry, Long> REVERSED_BY_ID = DEF.integer("reversedById", "reversed_by_id",
      e -> e.reversedById, (e, v) -> e.reversedById = v).readOnly().filterable();
  public static final Field<JournalEntry, String> CURRENCY_CODE = DEF.string("currencyCode", "currency_code",
      e -> e.currencyCode, (e, v) -> e.currencyCode = v).readOnly();
  public static final Field<JournalEntry, BigDecimal> TOTAL = DEF.decimal("total", "total", e -> e.total,
      (e, v) -> e.total = v).readOnly().filterable().sortable();
  public static final Field<JournalEntry, Instant> POSTED_AT = DEF.instant("postedAt", "posted_at",
      e -> e.postedAt, (e, v) -> e.postedAt = v).readOnly().sortable();
  public static final Field<JournalEntry, Long> POSTED_BY = DEF.integer("postedBy", "posted_by", e -> e.postedBy,
      (e, v) -> e.postedBy = v).readOnly();

  public static final Relation<JournalEntry, Period> PERIOD_REL = DEF.manyToOne("period", () -> Period.DEF,
      "period_id");
  public static final Relation<JournalEntry, JournalEntry> REVERSAL_OF_REL = DEF.manyToOne("reversalOf",
      () -> JournalEntry.DEF, "reversal_of_id");
  public static final Relation<JournalEntry, JournalEntry> REVERSED_BY_REL = DEF.manyToOne("reversedBy",
      () -> JournalEntry.DEF, "reversed_by_id");
  public static final Relation<JournalEntry, JournalLine> LINES_REL = DEF.oneToMany("lines", () -> JournalLine.DEF,
      "entry_id");

  /** UI hints (docs/ARCHITECTURE.md §23.4): newest first, amounts in the company's currency. */
  static {
    TOTAL.money();
    DEF.subtitleField(DESCRIPTION)
        .listColumns(ENTRY_NO, POSTING_DATE, DESCRIPTION, SOURCE_TYPE, TOTAL)
        .defaultSort(SortKey.desc(POSTING_DATE), SortKey.desc(ENTRY_NO));
  }

  private Long id;
  private Long companyId;
  private String entryNo;
  private LocalDate postingDate;
  private Long periodId;
  private String description;
  private String sourceType;
  private Long sourceId;
  private String sourceNo;
  private Long reversalOfId;
  private Long reversedById;
  private String currencyCode;
  private BigDecimal total;
  private Instant postedAt;
  private Long postedBy;
}

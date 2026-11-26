package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;

/** A journal entry's line, for the generic reads: the entry page's table, _refs to its account. Read-only. */
public final class JournalLine {

  public static final EntityDefinition<JournalLine> DEF = EntityDefinition.of("glJournalLine", "gl_journal_lines",
      JournalLine::new);

  public static final Field<JournalLine, Long> ID = DEF.id("id", l -> l.id, (l, v) -> l.id = v);
  public static final Field<JournalLine, Long> COMPANY_ID = DEF.companyId(l -> l.companyId,
      (l, v) -> l.companyId = v);
  public static final Field<JournalLine, Long> ENTRY_ID = DEF.integer("entryId", "entry_id", l -> l.entryId,
      (l, v) -> l.entryId = v).readOnly().filterable();
  public static final Field<JournalLine, Long> LINE_NO = DEF.integer("lineNo", "line_no", l -> l.lineNo,
      (l, v) -> l.lineNo = v).readOnly().sortable();
  public static final Field<JournalLine, LocalDate> POSTING_DATE = DEF.date("postingDate", "posting_date",
      l -> l.postingDate, (l, v) -> l.postingDate = v).readOnly().filterable().sortable();
  public static final Field<JournalLine, Long> ACCOUNT_ID = DEF.integer("accountId", "account_id",
      l -> l.accountId, (l, v) -> l.accountId = v).readOnly().filterable();
  public static final Field<JournalLine, BigDecimal> DEBIT = DEF.decimal("debit", "debit", l -> l.debit,
      (l, v) -> l.debit = v).readOnly();
  public static final Field<JournalLine, BigDecimal> CREDIT = DEF.decimal("credit", "credit", l -> l.credit,
      (l, v) -> l.credit = v).readOnly();
  public static final Field<JournalLine, String> DESCRIPTION = DEF.string("description", "description",
      l -> l.description, (l, v) -> l.description = v).readOnly();
  public static final Field<JournalLine, Long> DIMENSION_SET_ID = DEF.integer("dimensionSetId", "dimension_set_id",
      l -> l.dimensionSetId, (l, v) -> l.dimensionSetId = v).readOnly();

  public static final Relation<JournalLine, JournalEntry> ENTRY_REL = DEF.manyToOne("entry",
      () -> JournalEntry.DEF, "entry_id");
  public static final Relation<JournalLine, Account> ACCOUNT_REL = DEF.manyToOne("account", () -> Account.DEF,
      "account_id");

  static {
    DEBIT.money();
    CREDIT.money();
    DEF.titleField(LINE_NO);
  }

  private Long id;
  private Long companyId;
  private Long entryId;
  private Long lineNo;
  private LocalDate postingDate;
  private Long accountId;
  private BigDecimal debit;
  private BigDecimal credit;
  private String description;
  private Long dimensionSetId;
}

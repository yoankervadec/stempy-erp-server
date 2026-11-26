package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A gl_journal_entries row. */
public record StoredEntry(long id, long companyId, String entryNo, LocalDate postingDate, long periodId,
    String description, String sourceType, Long sourceId, String sourceNo, Long reversalOfId, Long reversedById,
    String currencyCode, BigDecimal total, Instant postedAt, long postedBy) {

  /** The audit log's entity name for a journal entry. */
  public static final String AUDIT_ENTITY = "glJournalEntry";
}

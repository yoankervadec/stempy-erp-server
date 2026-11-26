package com.lesconstructionssapete.stempyerpserver.modules.gl.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A journal entry as the API returns it. Amounts are in {@code currency},
 * with its number of decimals.
 *
 * @param lines null in lists (GET /gl/journal-entries), present for one entry
 */
public record JournalEntryView(long id, String entryNo, LocalDate postingDate, long periodId, String description,
    String sourceType, Long sourceId, String sourceNo, Long reversalOfId, Long reversedById, String currency,
    BigDecimal total, Instant postedAt, long postedBy, List<Line> lines) {

  /**
   * One line.
   *
   * @param debit      zero when the line is a credit
   * @param dimensions dimension code -> value code; empty when none
   */
  public record Line(int lineNo, long accountId, String accountNo, String accountName, BigDecimal debit,
      BigDecimal credit, String description, Map<String, String> dimensions) {
  }
}

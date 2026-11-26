package com.lesconstructionssapete.stempyerpserver.modules.gl.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountType;

/**
 * One account's lines over a date range, with a running balance. Amounts are
 * signed, debit-positive, in {@code currency}.
 *
 * @param dimension the dimension code filtered on, or null
 * @param value     the value code filtered on, or null
 */
public record AccountLedgerView(long accountId, String accountNo, String name, AccountType accountType,
    LocalDate from, LocalDate to, String currency, String dimension, String value, BigDecimal opening,
    BigDecimal debits, BigDecimal credits, BigDecimal closing, List<Line> lines) {

  /**
   * @param description the line's own, or else its entry's
   * @param balance     the running balance after this line
   */
  public record Line(long entryId, String entryNo, int lineNo, LocalDate postingDate, String description,
      String sourceType, String sourceNo, BigDecimal debit, BigDecimal credit, BigDecimal balance,
      Map<String, String> dimensions) {
  }
}

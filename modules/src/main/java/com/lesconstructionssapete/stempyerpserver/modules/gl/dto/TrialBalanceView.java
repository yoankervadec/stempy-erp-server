package com.lesconstructionssapete.stempyerpserver.modules.gl.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountType;

/**
 * A trial balance. Amounts are signed, debit-positive (a credit balance is
 * negative), in {@code currency}: closing = opening + debits - credits.
 *
 * @param dimension     the dimension code filtered on, or null
 * @param value         the value code filtered on, or null
 * @param rows          the chart in account-number order, each heading
 *                      before its accounts (depth in {@code level}); a heading
 *                      shows the sum of everything under it
 * @param totals        over posting accounts only: opening and closing are
 *                      zero, and debits = credits, when the books balance
 */
public record TrialBalanceView(LocalDate from, LocalDate to, String currency, String dimension, String value,
    List<Row> rows, Amounts totals) {

  public record Row(long accountId, String accountNo, String name, AccountType accountType, Long parentId,
      boolean isHeading, boolean isEnabled, int level, Amounts amounts) {
  }

  public record Amounts(BigDecimal opening, BigDecimal debits, BigDecimal credits, BigDecimal closing) {
  }
}

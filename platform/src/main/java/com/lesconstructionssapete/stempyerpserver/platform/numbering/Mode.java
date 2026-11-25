package com.lesconstructionssapete.stempyerpserver.platform.numbering;

/** How a number series takes its numbers (docs/ARCHITECTURE.md §21.1). Stored by name. */
public enum Mode {

  /**
   * In the caller's transaction: a rollback gives the number back, and the
   * counter stays locked until the caller commits. Invoices, journal entries.
   */
  GAPLESS,

  /**
   * In a short transaction of its own, committed at once: a rollback loses the
   * number, and nobody waits. Customer orders, quotes.
   */
  GAPS_ALLOWED
}

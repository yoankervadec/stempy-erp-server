package com.lesconstructionssapete.stempyerpserver.modules.gl;

/** What an account records; stored by name (gl_accounts.account_type). */
public enum AccountType {
  ASSET(true),
  LIABILITY(false),
  EQUITY(false),
  REVENUE(false),
  EXPENSE(true);

  private final boolean debitNormal;

  AccountType(boolean debitNormal) {
    this.debitNormal = debitNormal;
  }

  /** True when a balance is normally a debit (assets, expenses); reports show the other side as negative. */
  public boolean isDebitNormal() {
    return debitNormal;
  }
}

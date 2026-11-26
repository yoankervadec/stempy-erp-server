package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRule;
import com.lesconstructionssapete.stempyerpserver.modules.gl.JournalRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredAccount;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredPeriod;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * What posting an entry needs from the database, read in the posting
 * transaction: the company's currency, the period of the posting date and the
 * accounts, both with shared locks held until the commit (a period can't close
 * and an account can't become a heading under the entry), and the accounts'
 * dimension rules.
 */
public class LoadPostingContextOperation implements Operation<LoadPostingContextOperation.Input,
    LoadPostingContextOperation.Output> {

  /** @param accountNos the numbers the lines use (any case) */
  public record Input(long companyId, LocalDate postingDate, Set<String> accountNos) {
  }

  /**
   * @param accounts by upper-case account number
   * @param rules    account id -> upper-case dimension code -> rule
   */
  public record Output(String currencyCode, Optional<StoredPeriod> period, Map<String, StoredAccount> accounts,
      Map<Long, Map<String, DimensionRule>> rules) {
  }

  private final JournalRepository journal;
  private final PeriodRepository periods;
  private final AccountRepository accounts;
  private final DimensionRepository dimensions;

  public LoadPostingContextOperation(JournalRepository journal, PeriodRepository periods, AccountRepository accounts,
      DimensionRepository dimensions) {
    this.journal = journal;
    this.periods = periods;
    this.accounts = accounts;
    this.dimensions = dimensions;
  }

  @Override
  public Output execute(UnitOfWork uow, Input in) {
    String currency = journal.companyCurrency(uow, in.companyId())
        .orElseThrow(() -> new IllegalStateException("company " + in.companyId() + " not found"));
    Optional<StoredPeriod> period = periods.findPeriodForPosting(uow, in.companyId(), in.postingDate());
    Map<String, StoredAccount> byNo = new HashMap<>();
    for (StoredAccount a : accounts.findByNumbersForPosting(uow, in.companyId(), in.accountNos())) {
      byNo.put(a.fields().accountNo().toUpperCase(Locale.ROOT), a);
    }
    Map<Long, Map<String, DimensionRule>> rules = new HashMap<>();
    for (var r : dimensions.rulesOf(uow, byNo.values().stream().map(StoredAccount::id).toList())) {
      rules.computeIfAbsent(r.accountId(), k -> new HashMap<>()).put(r.dimensionCode().toUpperCase(Locale.ROOT),
          r.rule());
    }
    return new Output(currency, period, byNo, rules);
  }
}

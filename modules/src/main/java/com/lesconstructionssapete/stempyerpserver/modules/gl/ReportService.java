package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository.ChartRow;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository.LedgerRow;
import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportRepository.MovementRow;
import com.lesconstructionssapete.stempyerpserver.modules.gl.dto.AccountLedgerView;
import com.lesconstructionssapete.stempyerpserver.modules.gl.dto.TrialBalanceView;
import com.lesconstructionssapete.stempyerpserver.modules.gl.dto.TrialBalanceView.Amounts;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.LoadAccountLedgerOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.LoadTrialBalanceOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * GL reports (orchestrator), docs/ARCHITECTURE.md §22.6: the trial balance and
 * the account ledger, computed from the lines. Needs gl.report.read; the
 * request's company only.
 *
 * Amounts are signed, debit-positive. Openings are cumulative from the first
 * entry for every account: there is no year-end close yet (§22.11), so revenue
 * and expense accounts don't restart at the fiscal year.
 */
public class ReportService {

  public static final String READ = "gl.report.read";

  /** The most lines an account ledger returns; a longer range is refused. */
  static final int MAX_LEDGER_LINES = 5000;

  private final UnitOfWorkFactory uowFactory;
  private final PermissionChecker permissions;
  private final DimensionService dimensions;
  private final LoadTrialBalanceOperation loadTrialBalance;
  private final LoadAccountLedgerOperation loadLedger;

  public ReportService(UnitOfWorkFactory uowFactory, PermissionChecker permissions, DimensionService dimensions,
      LoadTrialBalanceOperation loadTrialBalance, LoadAccountLedgerOperation loadLedger) {
    this.uowFactory = uowFactory;
    this.permissions = permissions;
    this.dimensions = dimensions;
    this.loadTrialBalance = loadTrialBalance;
    this.loadLedger = loadLedger;
  }

  /** A filter on one dimension value: both null, or both set. */
  public record DimensionFilter(String dimension, String value) {

    static final DimensionFilter NONE = new DimensionFilter(null, null);

    boolean isSet() {
      return dimension != null;
    }
  }

  // ---- trial balance ----

  /**
   * @param includeZero false: posting accounts with no opening and no movement
   *                    are left out, and headings with nothing left under them
   */
  public TrialBalanceView trialBalance(RequestContext rc, LocalDate from, LocalDate to, DimensionFilter filter,
      boolean includeZero) {
    permissions.require(rc, READ);
    checkRange(from, to, filter);
    var data = uowFactory.executeReadOnly(uow -> loadTrialBalance.execute(uow, new LoadTrialBalanceOperation.Input(
        rc.companyId(), from, to, filter.dimension(), filter.value())));
    requireValue(data.valueFound(), filter);
    int digits = Currency.getInstance(data.currency()).getDefaultFractionDigits();

    Map<Long, MovementRow> moves = new HashMap<>();
    data.movements().forEach(m -> moves.put(m.accountId(), m));
    Map<Long, List<ChartRow>> children = new HashMap<>();
    List<ChartRow> roots = new ArrayList<>();
    Map<Long, ChartRow> byId = new HashMap<>();
    data.chart().forEach(a -> byId.put(a.id(), a));
    for (ChartRow a : data.chart()) {
      if (a.parentId() == null || !byId.containsKey(a.parentId())) {
        roots.add(a);
      } else {
        children.computeIfAbsent(a.parentId(), k -> new ArrayList<>()).add(a);
      }
    }
    Comparator<ChartRow> byNo = Comparator.comparing(ChartRow::accountNo);
    roots.sort(byNo);
    children.values().forEach(list -> list.sort(byNo));

    List<TrialBalanceView.Row> rows = new ArrayList<>();
    for (ChartRow root : roots) {
      addTree(root, 0, children, moves, includeZero, digits, rows);
    }
    BigDecimal[] total = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
    data.movements().forEach(m -> {
      total[0] = total[0].add(m.opening());
      total[1] = total[1].add(m.debits());
      total[2] = total[2].add(m.credits());
    });
    return new TrialBalanceView(from, to, data.currency(), filter.dimension(), filter.value(), rows,
        amounts(total[0], total[1], total[2], digits));
  }

  /**
   * Adds {@code account} and everything under it, depth first; returns its
   * own and its descendants' sums (opening, debits, credits), or null when
   * nothing of it is shown.
   */
  private static BigDecimal[] addTree(ChartRow account, int level, Map<Long, List<ChartRow>> children,
      Map<Long, MovementRow> moves, boolean includeZero, int digits, List<TrialBalanceView.Row> rows) {
    MovementRow own = moves.get(account.id());
    BigDecimal[] sum = own == null ? new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO }
        : new BigDecimal[] { own.opening(), own.debits(), own.credits() };
    int at = rows.size();
    boolean anyChildShown = false;
    for (ChartRow child : children.getOrDefault(account.id(), List.of())) {
      BigDecimal[] c = addTree(child, level + 1, children, moves, includeZero, digits, rows);
      if (c != null) {
        anyChildShown = true;
        sum[0] = sum[0].add(c[0]);
        sum[1] = sum[1].add(c[1]);
        sum[2] = sum[2].add(c[2]);
      }
    }
    boolean hasAmounts = sum[0].signum() != 0 || sum[1].signum() != 0 || sum[2].signum() != 0;
    if (!includeZero && !hasAmounts && !anyChildShown) {
      return null;
    }
    rows.add(at, new TrialBalanceView.Row(account.id(), account.accountNo(), account.name(), account.type(),
        account.parentId(), account.heading(), account.enabled(), level, amounts(sum[0], sum[1], sum[2], digits)));
    return sum;
  }

  // ---- account ledger ----

  public AccountLedgerView accountLedger(RequestContext rc, String accountNo, LocalDate from, LocalDate to,
      DimensionFilter filter) {
    permissions.require(rc, READ);
    checkRange(from, to, filter);
    var data = uowFactory.executeReadOnly(uow -> loadLedger.execute(uow, new LoadAccountLedgerOperation.Input(
        rc.companyId(), accountNo, from, to, filter.dimension(), filter.value(), MAX_LEDGER_LINES + 1)));
    requireValue(data.valueFound(), filter);
    var v = Validation.check();
    if (data.account().isEmpty()) {
      v.add("accountNo", "UNKNOWN_ACCOUNT", "no account " + accountNo + " in this company");
    } else if (data.account().get().heading()) {
      v.add("accountNo", "HEADING_ACCOUNT", "a heading has no lines of its own; see the trial balance");
    } else if (data.rows().size() > MAX_LEDGER_LINES) {
      v.add("to", "TOO_MANY_LINES", "more than " + MAX_LEDGER_LINES + " lines; narrow the date range");
    }
    v.throwIfInvalid();

    ChartRow account = data.account().get();
    int digits = Currency.getInstance(data.currency()).getDefaultFractionDigits();
    Map<Long, Map<String, String>> sets = new HashMap<>();
    BigDecimal balance = data.opening();
    BigDecimal debits = BigDecimal.ZERO;
    BigDecimal credits = BigDecimal.ZERO;
    List<AccountLedgerView.Line> lines = new ArrayList<>();
    for (LedgerRow r : data.rows()) {
      balance = balance.add(r.debit()).subtract(r.credit());
      debits = debits.add(r.debit());
      credits = credits.add(r.credit());
      Map<String, String> dims = r.dimensionSetId() == null ? Map.of()
          : sets.computeIfAbsent(r.dimensionSetId(), id -> new LinkedHashMap<>(dimensions.describeSet(id)));
      lines.add(new AccountLedgerView.Line(r.entryId(), r.entryNo(), r.lineNo(), r.postingDate(), r.description(),
          r.sourceType(), r.sourceNo(), scaled(r.debit(), digits), scaled(r.credit(), digits),
          scaled(balance, digits), dims));
    }
    return new AccountLedgerView(account.id(), account.accountNo(), account.name(), account.type(), from, to,
        data.currency(), filter.dimension(), filter.value(), scaled(data.opening(), digits), scaled(debits, digits),
        scaled(credits, digits), scaled(balance, digits), lines);
  }

  // ---- shared ----

  private static void checkRange(LocalDate from, LocalDate to, DimensionFilter filter) {
    var v = Validation.check();
    v.required("from", from).required("to", to);
    if (from != null && to != null && to.isBefore(from)) {
      v.add("to", "BEFORE_FROM", "must be on or after from");
    }
    if ((filter.dimension() == null) != (filter.value() == null)) {
      v.add(filter.dimension() == null ? "dimension" : "value", "REQUIRED",
          "a dimension filter needs both dimension and value");
    }
    v.throwIfInvalid();
  }

  private static void requireValue(boolean found, DimensionFilter filter) {
    if (!found) {
      var v = Validation.check();
      v.add("value", "UNKNOWN_VALUE", "dimension " + filter.dimension() + " has no value " + filter.value());
      v.throwIfInvalid();
    }
  }

  private static Amounts amounts(BigDecimal opening, BigDecimal debits, BigDecimal credits, int digits) {
    return new Amounts(scaled(opening, digits), scaled(debits, digits), scaled(credits, digits),
        scaled(opening.add(debits).subtract(credits), digits));
  }

  /** Lines are stored rounded: only the trailing zeros of DECIMAL(19,4) go. */
  private static BigDecimal scaled(BigDecimal amount, int digits) {
    return amount.setScale(digits, RoundingMode.UNNECESSARY);
  }

  /** For the controller: no filter. */
  public static DimensionFilter noFilter() {
    return DimensionFilter.NONE;
  }
}

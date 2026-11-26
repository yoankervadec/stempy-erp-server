package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.gl.events.JournalEntryPosted;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindJournalEntryOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.InsertJournalEntryOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListJournalLinesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.LoadPostingContextOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.MarkReversedOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.NumberRequest;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Posting and reversing journal entries (orchestrator), docs/ARCHITECTURE.md
 * §22.5. Published as GeneralLedgerApi; JournalService (HTTP) uses it too.
 * Works in the caller's UnitOfWork; permissions are the caller's business.
 *
 * Order of a posting:
 * <ol>
 * <li>the request's shape (dates, texts, amounts, sides);</li>
 * <li>dimension sets, resolved and created in short transactions of their own
 * (DimensionService), before any lock is taken here;</li>
 * <li>in {@code uow}: the period of the posting date and the accounts, with
 * shared locks until the commit; then accounts, rounding, balance and
 * dimension rules;</li>
 * <li>every problem so far as one ValidationFailedException; then the period
 * (409 PERIOD_NOT_FOUND / PERIOD_CLOSED);</li>
 * <li>a gapless number, the rows, the audit entry, JournalEntryPosted.</li>
 * </ol>
 */
public class JournalPostingService implements GeneralLedgerApi {

  /** The document type of journal entries (gaplessRequired, default series JE{SEQ:8}). */
  public static final String DOCUMENT_TYPE = "gl.journal-entry";

  static final int MAX_LINES = 1000;
  /** DECIMAL(19,4) holds 15 integer digits. */
  static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.99");

  private final DimensionService dimensions;
  private final DocumentNumbers numbers;
  private final EventBus eventBus;
  private final Clock clock;
  private final LoadPostingContextOperation loadContext;
  private final InsertJournalEntryOperation insertEntry;
  private final FindJournalEntryOperation findEntry;
  private final ListJournalLinesOperation listLines;
  private final MarkReversedOperation markReversed;

  public JournalPostingService(DimensionService dimensions, DocumentNumbers numbers, EventBus eventBus, Clock clock,
      LoadPostingContextOperation loadContext, InsertJournalEntryOperation insertEntry,
      FindJournalEntryOperation findEntry, ListJournalLinesOperation listLines, MarkReversedOperation markReversed) {
    this.dimensions = dimensions;
    this.numbers = numbers;
    this.eventBus = eventBus;
    this.clock = clock;
    this.loadContext = loadContext;
    this.insertEntry = insertEntry;
    this.findEntry = findEntry;
    this.listLines = listLines;
    this.markReversed = markReversed;
  }

  // ---- posting ----

  @Override
  public JournalEntryRef post(UnitOfWork uow, RequestContext rc, JournalEntryRequest request) {
    Objects.requireNonNull(uow, "uow");
    Objects.requireNonNull(request, "request");
    long companyId = rc.companyId();
    boolean manual = MANUAL.equals(request.sourceType());
    var v = Validation.check();
    boolean amountsValid = checkShape(v, request);

    // Dimension sets first: they commit on their own, before this transaction locks anything.
    List<Long> setIds = new ArrayList<>();
    for (int i = 0; i < request.lines().size(); i++) {
      LineRequest line = request.lines().get(i);
      Long setId = null;
      if (!line.dimensions().isEmpty()) {
        try {
          setId = dimensions.resolveSet(companyId, line.dimensions(), "lines[" + i + "].dimensions").orElse(null);
        } catch (ValidationFailedException e) {
          for (FieldError fe : e.fieldErrors()) {
            v.add(fe.field(), fe.code(), fe.message());
          }
        }
      }
      setIds.add(setId);
    }

    Set<String> accountNos = new HashSet<>();
    request.lines().stream().map(LineRequest::accountNo).filter(Objects::nonNull).forEach(accountNos::add);
    LocalDate date = request.postingDate() == null ? LocalDate.EPOCH : request.postingDate();
    var ctx = loadContext.execute(uow, new LoadPostingContextOperation.Input(companyId, date, accountNos));

    int digits = Currency.getInstance(ctx.currencyCode()).getDefaultFractionDigits();
    BigDecimal debits = BigDecimal.ZERO;
    BigDecimal credits = BigDecimal.ZERO;
    List<PostingLine> lines = new ArrayList<>();
    for (int i = 0; i < request.lines().size(); i++) {
      LineRequest line = request.lines().get(i);
      String at = "lines[" + i + "]";
      BigDecimal debit = orZero(line.debit());
      BigDecimal credit = orZero(line.credit());
      if (debit.stripTrailingZeros().scale() > digits) {
        v.add(at + ".debit", "NOT_ROUNDED", "at most " + digits + " decimals in " + ctx.currencyCode());
      }
      if (credit.stripTrailingZeros().scale() > digits) {
        v.add(at + ".credit", "NOT_ROUNDED", "at most " + digits + " decimals in " + ctx.currencyCode());
      }
      debits = debits.add(debit);
      credits = credits.add(credit);

      StoredAccount account = line.accountNo() == null ? null
          : ctx.accounts().get(line.accountNo().toUpperCase(Locale.ROOT));
      if (line.accountNo() != null) {
        checkAccount(v, at, line, account, manual, ctx.rules().getOrDefault(account == null ? -1L : account.id(),
            Map.of()));
      }
      if (account != null) {
        lines.add(new PostingLine(account.id(), debit, credit, blankToNull(line.description()), setIds.get(i)));
      }
    }
    // Only when every line has one valid side: otherwise the line errors say it better.
    if (amountsValid && request.lines().size() >= 2 && debits.compareTo(credits) != 0) {
      v.add("lines", "UNBALANCED", "debits (" + debits.toPlainString() + ") must equal credits ("
          + credits.toPlainString() + ")");
    }
    v.throwIfInvalid();

    StoredPeriod period = requireOpenPeriod(ctx.period(), request.postingDate());
    return insert(uow, rc, request.postingDate(), period.id(), request.description().strip(), request.sourceType(),
        request.sourceId(), blankToNull(request.sourceNo()), null, ctx.currencyCode(), debits, lines);
  }

  /**
   * What doesn't need the database: required texts, lengths, line count,
   * amounts and sides. Returns whether every line has exactly one valid side.
   */
  private static boolean checkShape(Validation v, JournalEntryRequest r) {
    boolean amountsValid = true;
    v.required("postingDate", r.postingDate());
    if (r.description() == null || r.description().isBlank()) {
      v.add("description", "REQUIRED", "is required");
    }
    v.maxLength("description", r.description(), 255);
    if (r.sourceType() == null || r.sourceType().isBlank()) {
      v.add("sourceType", "REQUIRED", "is required: MANUAL, or the posting module's document type");
    }
    v.maxLength("sourceType", r.sourceType(), 100).maxLength("sourceNo", r.sourceNo(), 50);
    if (r.lines().size() < 2) {
      v.add("lines", "TOO_FEW_LINES", "an entry needs at least two lines");
    } else if (r.lines().size() > MAX_LINES) {
      v.add("lines", "TOO_MANY_LINES", "at most " + MAX_LINES + " lines per entry");
    }
    for (int i = 0; i < r.lines().size(); i++) {
      LineRequest l = r.lines().get(i);
      String at = "lines[" + i + "]";
      if (l.accountNo() == null || l.accountNo().isBlank()) {
        v.add(at + ".accountNo", "REQUIRED", "is required");
      }
      v.maxLength(at + ".description", l.description(), 255);
      BigDecimal debit = orZero(l.debit());
      BigDecimal credit = orZero(l.credit());
      if (debit.signum() < 0) {
        v.add(at + ".debit", "NEGATIVE", "must be zero or more; use the other side instead");
        amountsValid = false;
      }
      if (credit.signum() < 0) {
        v.add(at + ".credit", "NEGATIVE", "must be zero or more; use the other side instead");
        amountsValid = false;
      }
      if (debit.signum() > 0 && credit.signum() > 0) {
        v.add(at, "BOTH_SIDES", "a line is either a debit or a credit, not both");
        amountsValid = false;
      } else if (debit.signum() == 0 && credit.signum() == 0) {
        v.add(at, "NO_AMOUNT", "a line needs a debit or a credit above zero");
        amountsValid = false;
      }
      if (debit.compareTo(MAX_AMOUNT) > 0) {
        v.add(at + ".debit", "OUT_OF_RANGE", "at most " + MAX_AMOUNT.toPlainString());
        amountsValid = false;
      }
      if (credit.compareTo(MAX_AMOUNT) > 0) {
        v.add(at + ".credit", "OUT_OF_RANGE", "at most " + MAX_AMOUNT.toPlainString());
        amountsValid = false;
      }
    }
    return amountsValid;
  }

  private static void checkAccount(Validation v, String at, LineRequest line, StoredAccount account, boolean manual,
      Map<String, DimensionRule> rules) {
    if (account == null) {
      v.add(at + ".accountNo", "UNKNOWN_ACCOUNT", "no account " + line.accountNo() + " in this company");
      return;
    }
    AccountFields a = account.fields();
    if (!a.isEnabled()) {
      v.add(at + ".accountNo", "ACCOUNT_DISABLED", "account " + a.accountNo() + " is disabled");
    }
    if (a.isHeading()) {
      v.add(at + ".accountNo", "HEADING_ACCOUNT", "account " + a.accountNo() + " is a heading: it takes no postings");
    }
    if (manual && !a.manualPosting()) {
      v.add(at + ".accountNo", "CONTROL_ACCOUNT", "account " + a.accountNo()
          + " is a control account: only its module posts there");
    }
    Set<String> given = new HashSet<>();
    line.dimensions().keySet().forEach(code -> given.add(code.toUpperCase(Locale.ROOT)));
    rules.forEach((code, rule) -> {
      if (rule == DimensionRule.REQUIRED && !given.contains(code)) {
        v.add(at + ".dimensions." + code, "DIMENSION_REQUIRED", "account " + a.accountNo() + " requires " + code);
      } else if (rule == DimensionRule.NOT_ALLOWED && given.contains(code)) {
        v.add(at + ".dimensions." + code, "DIMENSION_NOT_ALLOWED",
            "account " + a.accountNo() + " doesn't take " + code);
      }
    });
  }

  // ---- reversal ----

  /**
   * Posts the mirror of an entry (same accounts and dimensions, debit and
   * credit swapped) on {@code postingDate}, and links both. The original may
   * sit in a closed period; the reversal's date must be in an open one.
   * Accounts and dimension values disabled since are accepted: a reversal
   * corrects what was posted.
   *
   * @param description null: "Reversal of {entryNo}"
   * @throws ResourceNotFoundException no such entry in rc's company
   * @throws ConflictException         ALREADY_REVERSED, CANNOT_REVERSE_A_REVERSAL,
   *                                   PERIOD_NOT_FOUND, PERIOD_CLOSED
   */
  public JournalEntryRef reverse(UnitOfWork uow, RequestContext rc, long entryId, LocalDate postingDate,
      String description) {
    StoredEntry original = findEntry.execute(uow, new FindJournalEntryOperation.Input(entryId, true))
        .filter(e -> e.companyId() == rc.companyId())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Journal entry " + entryId + " not found"));
    if (original.reversalOfId() != null) {
      throw new ConflictException("CANNOT_REVERSE_A_REVERSAL",
          "This entry is itself a reversal; post a new entry instead");
    }
    if (original.reversedById() != null) {
      throw new ConflictException("ALREADY_REVERSED", "The entry has already been reversed");
    }
    var ctx = loadContext.execute(uow, new LoadPostingContextOperation.Input(rc.companyId(), postingDate, Set.of()));
    StoredPeriod period = requireOpenPeriod(ctx.period(), postingDate);
    List<PostingLine> mirrored = listLines.execute(uow, entryId).stream()
        .map(l -> new PostingLine(l.accountId(), l.credit(), l.debit(), l.description(), l.dimensionSetId()))
        .toList();
    String text = description == null || description.isBlank() ? "Reversal of " + original.entryNo()
        : description.strip();
    JournalEntryRef ref = insert(uow, rc, postingDate, period.id(), text, original.sourceType(), original.sourceId(),
        original.sourceNo(), original.id(), original.currencyCode(), original.total(), mirrored);
    markReversed.execute(uow, new MarkReversedOperation.Input(original.id(), original.companyId(), ref.id(),
        ref.entryNo(), rc.userId(), rc.requestId(), clock.instant()));
    return ref;
  }

  // ---- shared ----

  private static StoredPeriod requireOpenPeriod(Optional<StoredPeriod> period, LocalDate date) {
    StoredPeriod p = period.orElseThrow(() -> new ConflictException("PERIOD_NOT_FOUND",
        "No period covers " + date + "; create the fiscal year first"));
    if (p.status() != PeriodStatus.OPEN) {
      throw new ConflictException("PERIOD_CLOSED", "The period " + p.startDate().toString().substring(0, 7)
          + " is closed; post in an open period or have it reopened");
    }
    return p;
  }

  /** Numbers (gapless, last thing before the insert), inserts, publishes. */
  private JournalEntryRef insert(UnitOfWork uow, RequestContext rc, LocalDate date, long periodId,
      String description, String sourceType, Long sourceId, String sourceNo, Long reversalOfId, String currency,
      BigDecimal total, List<PostingLine> lines) {
    Instant now = clock.instant();
    String entryNo = numbers.next(uow, NumberRequest.of(rc.companyId(), DOCUMENT_TYPE));
    long id = insertEntry.execute(uow, new InsertJournalEntryOperation.Input(rc.companyId(), entryNo, date,
        periodId, description, sourceType, sourceId, sourceNo, reversalOfId, currency, total, lines, rc.userId(),
        rc.requestId(), now));
    eventBus.publish(uow, new JournalEntryPosted(rc.companyId(), id, entryNo, date, sourceType, sourceId,
        reversalOfId, total));
    return new JournalEntryRef(id, entryNo);
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}

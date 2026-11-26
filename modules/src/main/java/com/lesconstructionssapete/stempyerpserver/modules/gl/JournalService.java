package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.dto.JournalEntryView;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindJournalEntryOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListJournalEntriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListJournalLinesOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditHistory;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditRecord;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.idempotency.Idempotency;
import com.lesconstructionssapete.stempyerpserver.platform.idempotency.IdempotencyKey;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Journal entries over HTTP (orchestrator), docs/ARCHITECTURE.md §22.5: manual
 * posting with an optional Idempotency-Key, reading, reversal, history. The
 * request's company only; another company's entries are 404.
 *
 * Permissions: gl.journal.read, gl.journal.post (manual entries),
 * gl.journal.reverse.
 */
public class JournalService {

  public static final String READ = "gl.journal.read";
  public static final String POST = "gl.journal.post";
  public static final String REVERSE = "gl.journal.reverse";

  /** The idempotency scope of manual posting. */
  static final String POST_SCOPE = "POST /gl/journal-entries";

  /** A posted (or replayed) entry. */
  public record Posted(JournalEntryView entry, boolean replayed) {
  }

  private final UnitOfWorkFactory uowFactory;
  private final PermissionChecker permissions;
  private final JournalPostingService posting;
  private final DimensionService dimensions;
  private final Idempotency idempotency;
  private final FindJournalEntryOperation findEntry;
  private final ListJournalLinesOperation listLines;
  private final ListJournalEntriesOperation listEntries;
  private final AuditLog audit;
  private final Optional<UserDirectory> userDirectory;

  public JournalService(UnitOfWorkFactory uowFactory, PermissionChecker permissions, JournalPostingService posting,
      DimensionService dimensions, Idempotency idempotency, FindJournalEntryOperation findEntry,
      ListJournalLinesOperation listLines, ListJournalEntriesOperation listEntries, AuditLog audit,
      Optional<UserDirectory> userDirectory) {
    this.uowFactory = uowFactory;
    this.permissions = permissions;
    this.posting = posting;
    this.dimensions = dimensions;
    this.idempotency = idempotency;
    this.findEntry = findEntry;
    this.listLines = listLines;
    this.listEntries = listEntries;
    this.audit = audit;
    this.userDirectory = userDirectory;
  }

  /**
   * Posts a manual entry. With an Idempotency-Key, a retry of the same request
   * returns the entry the first one posted ({@code replayed}) instead of
   * posting it twice; the key and the entry commit together.
   *
   * @param idempotencyHeader the Idempotency-Key header, or null
   */
  public Posted postManual(RequestContext rc, JsonNode body, String idempotencyHeader) {
    permissions.require(rc, POST);
    var request = JournalForm.manualEntry(body);
    IdempotencyKey key = IdempotencyKey.fromHeader(rc.companyId(), rc.userId(), POST_SCOPE, idempotencyHeader);
    String hash = Idempotency.hash(body.toString());
    boolean[] replayed = { false };
    long id = uowFactory.execute(uow -> {
      if (key != null) {
        Optional<String> done = idempotency.begin(uow, key, hash);
        if (done.isPresent()) {
          replayed[0] = true;
          return Long.parseLong(done.get());
        }
      }
      long posted = posting.post(uow, rc, request).id();
      if (key != null) {
        idempotency.complete(uow, key, String.valueOf(posted));
      }
      return posted;
    });
    return new Posted(view(rc, id), replayed[0]);
  }

  public JournalEntryView getEntry(RequestContext rc, long id) {
    permissions.require(rc, READ);
    return view(rc, id);
  }

  /** Newest posting date first; lines left out (null). */
  public List<JournalEntryView> listEntries(RequestContext rc, LocalDate from, LocalDate to, String sourceType,
      int limit, int offset) {
    permissions.require(rc, READ);
    return uowFactory.executeReadOnly(uow -> listEntries.execute(uow, new ListJournalEntriesOperation.Input(
        rc.companyId(), from, to, sourceType, limit, offset))).stream()
        .map(e -> view(e, null)).toList();
  }

  /** {"postingDate": "...", "description": optional}: posts the reversal and returns it. */
  public JournalEntryView reverse(RequestContext rc, long id, JsonNode body) {
    permissions.require(rc, REVERSE);
    var form = JournalForm.reversal(body);
    long reversalId = uowFactory.execute(uow -> posting.reverse(uow, rc, id, form.postingDate(),
        form.description()).id());
    return view(rc, reversalId);
  }

  /** POSTED and REVERSED entries, newest first. Needs audit.history.read on top of read. */
  public List<Map<String, Object>> history(RequestContext rc, long id, int limit, int offset) {
    permissions.require(rc, READ);
    StoredEntry e = visible(rc, id);
    permissions.require(rc, AuditLog.READ_PERMISSION);
    return uowFactory.executeReadOnly(uow -> {
      List<AuditRecord> records = audit.history(uow, StoredEntry.AUDIT_ENTITY, id, e.companyId(), limit, offset);
      Set<Long> userIds = records.stream().map(AuditRecord::userId).filter(Objects::nonNull)
          .collect(Collectors.toSet());
      Map<Long, UserDirectory.UserSummary> users = userIds.isEmpty() ? Map.of()
          : userDirectory.map(d -> d.find(uow, userIds)).orElse(Map.of());
      return AuditHistory.view(records, users);
    });
  }

  // ---- views ----

  private StoredEntry visible(RequestContext rc, long id) {
    return uowFactory.executeReadOnly(uow -> findEntry.execute(uow,
        new FindJournalEntryOperation.Input(rc.companyId(), id, false)))
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Journal entry " + id + " not found"));
  }

  /** One entry with its lines and their dimensions. */
  private JournalEntryView view(RequestContext rc, long id) {
    StoredEntry e = visible(rc, id);
    List<StoredLine> lines = uowFactory.executeReadOnly(uow -> listLines.execute(uow, id));
    Map<Long, Map<String, String>> sets = new HashMap<>();
    int digits = Currency.getInstance(e.currencyCode()).getDefaultFractionDigits();
    List<JournalEntryView.Line> views = lines.stream()
        .map(l -> new JournalEntryView.Line(l.lineNo(), l.accountId(), l.accountNo(), l.accountName(),
            scaled(l.debit(), digits), scaled(l.credit(), digits), l.description(),
            l.dimensionSetId() == null ? Map.of()
                : sets.computeIfAbsent(l.dimensionSetId(), dimensions::describeSet)))
        .toList();
    return view(e, views);
  }

  private static JournalEntryView view(StoredEntry e, List<JournalEntryView.Line> lines) {
    int digits = Currency.getInstance(e.currencyCode()).getDefaultFractionDigits();
    return new JournalEntryView(e.id(), e.entryNo(), e.postingDate(), e.periodId(), e.description(), e.sourceType(),
        e.sourceId(), e.sourceNo(), e.reversalOfId(), e.reversedById(), e.currencyCode(), scaled(e.total(), digits),
        e.postedAt(), e.postedBy(), lines);
  }

  /** DECIMAL(19,4) as the currency shows it: 100.0000 -> 100.00 (amounts are stored rounded). */
  private static BigDecimal scaled(BigDecimal amount, int digits) {
    return amount.setScale(digits, RoundingMode.UNNECESSARY);
  }
}

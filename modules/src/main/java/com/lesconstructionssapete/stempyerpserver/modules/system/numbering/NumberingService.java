package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.dto.CounterView;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.dto.DocumentTypeView;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.dto.SeriesView;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.CreateSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.FindSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.ListCountersOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.ListSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.SetNextNumberOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations.UpdateSeriesOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditHistory;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditRecord;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.NumberPattern;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * The numbering admin API's rules (orchestrator), docs/ARCHITECTURE.md §21.7.
 *
 * <ul>
 * <li>Series of the request's company only; another company's are 404.
 * Reading needs numbering.series.read, changing numbering.series.manage.</li>
 * <li>A pattern must parse against its document type's parameters, and a
 * gaplessRequired type only accepts GAPLESS series.</li>
 * <li>The default can't be switched off directly: make another series the
 * default instead (an unset default would bring the type's built-in default
 * back on the next number).</li>
 * </ul>
 */
public class NumberingService {

  public static final String READ = "numbering.series.read";
  public static final String MANAGE = "numbering.series.manage";

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 500;

  /** limit / offset of a list request. */
  public record Page(int limit, int offset) {
  }

  /** A list and the page it is. */
  public record Listing<T>(List<T> data, Page page) {
  }

  private final UnitOfWorkFactory uowFactory;
  private final DocumentTypes types;
  private final PermissionChecker permissions;
  private final Clock clock;
  private final ListSeriesOperation listSeries;
  private final FindSeriesOperation findSeries;
  private final CreateSeriesOperation createSeries;
  private final UpdateSeriesOperation updateSeries;
  private final ListCountersOperation listCounters;
  private final SetNextNumberOperation setNextNumber;
  private final AuditLog audit;
  private final Optional<UserDirectory> userDirectory;

  public NumberingService(UnitOfWorkFactory uowFactory, DocumentTypes types, PermissionChecker permissions,
      Clock clock, ListSeriesOperation listSeries, FindSeriesOperation findSeries,
      CreateSeriesOperation createSeries, UpdateSeriesOperation updateSeries, ListCountersOperation listCounters,
      SetNextNumberOperation setNextNumber, AuditLog audit, Optional<UserDirectory> userDirectory) {
    this.uowFactory = uowFactory;
    this.types = types;
    this.permissions = permissions;
    this.clock = clock;
    this.listSeries = listSeries;
    this.findSeries = findSeries;
    this.createSeries = createSeries;
    this.updateSeries = updateSeries;
    this.listCounters = listCounters;
    this.setNextNumber = setNextNumber;
    this.audit = audit;
    this.userDirectory = userDirectory;
  }

  // ---- document types ----

  public List<DocumentTypeView> documentTypes(RequestContext rc) {
    permissions.require(rc, READ);
    return types.all().stream()
        .sorted(Comparator.comparing(DocumentType::key))
        .map(t -> new DocumentTypeView(t.key(), t.description(), t.parameters(), t.isGaplessRequired(),
            t.defaultSeries() == null ? null
                : new DocumentTypeView.DefaultSeriesView(t.defaultSeries().code(), t.defaultSeries().pattern(),
                    t.defaultSeries().mode())))
        .toList();
  }

  // ---- series ----

  /** @param documentType null: every type */
  public Listing<SeriesView> listSeries(RequestContext rc, String documentType, Page page) {
    permissions.require(rc, READ);
    var input = new ListSeriesOperation.Input(rc.companyId(), documentType, page.limit(), page.offset());
    List<SeriesView> rows = uowFactory.executeReadOnly(uow -> listSeries.execute(uow, input)).stream()
        .map(this::view).toList();
    return new Listing<>(rows, page);
  }

  public SeriesView getSeries(RequestContext rc, long id) {
    return view(visibleSeries(rc, id));
  }

  public SeriesView createSeries(RequestContext rc, JsonNode body) {
    permissions.require(rc, MANAGE);
    SeriesForm.Result form = SeriesForm.apply(body, null);
    var v = Validation.check();
    Optional<DocumentType> type = types.find(form.documentType());
    if (type.isEmpty()) {
      v.add("documentType", "UNKNOWN_DOCUMENT_TYPE", "no document type '" + form.documentType()
          + "' on this server (see GET /numbering/document-types)",
          Validation.params("documentType", form.documentType()));
    } else {
      checkAgainstType(v, type.get(), form.fields());
    }
    v.throwIfInvalid();

    Instant now = clock.instant();
    long id = uowFactory.execute(uow -> createSeries.execute(uow, new CreateSeriesOperation.Input(rc.companyId(),
        form.documentType(), form.fields(), form.defaultGiven(), rc.userId(), rc.requestId(), now)));
    return getSeries(rc, id);
  }

  public SeriesView updateSeries(RequestContext rc, long id, JsonNode body) {
    StoredSeries current = visibleSeries(rc, id);
    permissions.require(rc, MANAGE);
    SeriesForm.Result form = SeriesForm.apply(body, current.fields());
    var v = Validation.check();
    if (current.fields().isDefault() && !form.fields().isDefault()) {
      v.add("isDefault", "CANNOT_UNSET", "make another series the default instead");
    }
    SeriesFields before = current.fields();
    SeriesFields after = form.fields();
    boolean shapeChanged = !before.pattern().equals(after.pattern()) || before.mode() != after.mode();
    // Check what changed only: a series whose type was removed from the server can still be described or disabled.
    if (shapeChanged) {
      types.find(current.documentType()).ifPresentOrElse(t -> checkAgainstType(v, t, after),
          () -> v.add("documentType", "UNKNOWN_DOCUMENT_TYPE", "no module on this server declares "
              + current.documentType() + ": its pattern and mode can't be checked",
              Validation.params("documentType", current.documentType())));
    }
    v.throwIfInvalid();

    Instant now = clock.instant();
    uowFactory.run(uow -> updateSeries.execute(uow, new UpdateSeriesOperation.Input(id, form.version(), after,
        rc.userId(), rc.requestId(), now)));
    return getSeries(rc, id);
  }

  /**
   * The series' audit entries, newest first: who created or changed it, and
   * which counters were moved. Needs audit.history.read on top of read.
   */
  public Listing<Map<String, Object>> seriesHistory(RequestContext rc, long id, Page page) {
    StoredSeries s = visibleSeries(rc, id);
    permissions.require(rc, AuditLog.READ_PERMISSION);
    List<Map<String, Object>> items = uowFactory.executeReadOnly(uow -> {
      List<AuditRecord> records = audit.history(uow, SeriesFields.AUDIT_ENTITY, id, s.companyId(), page.limit(),
          page.offset());
      Set<Long> userIds = records.stream().map(AuditRecord::userId).filter(Objects::nonNull)
          .collect(Collectors.toSet());
      Map<Long, UserDirectory.UserSummary> users = userIds.isEmpty() ? Map.of()
          : userDirectory.map(d -> d.find(uow, userIds)).orElse(Map.of());
      return AuditHistory.view(records, users);
    });
    return new Listing<>(items, page);
  }

  // ---- counters ----

  public List<CounterView> counters(RequestContext rc, long id) {
    StoredSeries s = visibleSeries(rc, id);
    Optional<NumberPattern> pattern = pattern(s);
    return uowFactory.executeReadOnly(uow -> listCounters.execute(uow, id)).stream()
        .map(c -> view(c, pattern))
        .toList();
  }

  /**
   * Moves one counter so its next number is {@code nextNumber} (to continue an
   * old system's numbering), creating it if needed. Returns that counter.
   */
  public CounterView setNextNumber(RequestContext rc, long id, JsonNode body) {
    StoredSeries s = visibleSeries(rc, id);
    permissions.require(rc, MANAGE);
    SeriesForm.SetNext form = SeriesForm.setNext(body);
    NumberPattern pattern = pattern(s).orElseThrow(() -> new IllegalStateException("Number series " + id
        + " has a pattern its document type " + s.documentType() + " doesn't accept (or the type is gone)"));
    String scope = pattern.apply(form.params()).scope(); // ValidationFailedException: params.NAME

    Instant now = clock.instant();
    uowFactory.execute(uow -> setNextNumber.execute(uow, new SetNextNumberOperation.Input(id, s.companyId(), scope,
        form.nextNumber(), rc.userId(), rc.requestId(), now)));
    return counters(rc, id).stream().filter(c -> c.scope().equals(scope)).findFirst()
        .orElseThrow(() -> new IllegalStateException("counter " + id + "/" + scope + " not found after setting it"));
  }

  // ---- rules ----

  /** Another company's series is 404: its existence is not revealed. */
  private StoredSeries visibleSeries(RequestContext rc, long id) {
    StoredSeries s = uowFactory.executeReadOnly(uow -> findSeries.execute(uow, id))
        .filter(found -> found.companyId() == rc.companyId())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Number series " + id + " not found"));
    permissions.require(rc, READ);
    return s;
  }

  private static void checkAgainstType(Validation v, DocumentType type, SeriesFields f) {
    if (f.pattern() != null) {
      try {
        NumberPattern.parse(f.pattern(), type.parameters());
      } catch (IllegalArgumentException e) {
        v.add("pattern", "INVALID_PATTERN", e.getMessage());
      }
    }
    if (type.isGaplessRequired() && f.mode() == Mode.GAPS_ALLOWED) {
      v.add("mode", "GAPLESS_REQUIRED", type.key() + " must be numbered without gaps",
          Validation.params("documentType", type.key()));
    }
  }

  /** The stored pattern, if its type is registered and still accepts it. */
  private Optional<NumberPattern> pattern(StoredSeries s) {
    return types.find(s.documentType()).flatMap(t -> {
      try {
        return Optional.of(NumberPattern.parse(s.fields().pattern(), t.parameters()));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    });
  }

  // ---- views ----

  private SeriesView view(StoredSeries s) {
    SeriesFields f = s.fields();
    return new SeriesView(s.id(), s.version(), s.documentType(), types.find(s.documentType()).isPresent(), f.code(),
        f.description(), f.pattern(), f.mode(), f.startValue(), f.isDefault(), f.isEnabled(), s.used(),
        s.createdAt(), s.createdBy(), s.updatedAt(), s.updatedBy());
  }

  private static CounterView view(StoredCounter c, Optional<NumberPattern> pattern) {
    Map<String, String> params = scopeValues(c.scope());
    long next = c.lastNumber() + 1;
    String formatted = pattern.map(p -> {
      try {
        return p.apply(params).format(next);
      } catch (RuntimeException e) {
        return null; // values that no longer fit the pattern: shown without a sample
      }
    }).orElse(null);
    return new CounterView(c.scope(), params, c.lastNumber(), next, formatted, c.updatedAt());
  }

  /** {@code STORE_NO=012;TERMINAL_NO=03} -> {STORE_NO=012, TERMINAL_NO=03}. */
  static Map<String, String> scopeValues(String scope) {
    Map<String, String> values = new LinkedHashMap<>();
    if (scope.isEmpty()) {
      return values;
    }
    for (String entry : scope.split(";")) {
      int eq = entry.indexOf('=');
      if (eq > 0) {
        values.put(entry.substring(0, eq), entry.substring(eq + 1));
      }
    }
    return values;
  }
}

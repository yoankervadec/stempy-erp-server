package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Operator;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.query.FilterNode;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryExecutor;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.query.QuerySpec;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;
import com.lesconstructionssapete.stempyerpserver.platform.write.FieldChanges;
import com.lesconstructionssapete.stempyerpserver.platform.write.SQLWrite;
import com.lesconstructionssapete.stempyerpserver.platform.write.WriteBuilder;
import com.lesconstructionssapete.stempyerpserver.platform.write.WriteContext;
import com.lesconstructionssapete.stempyerpserver.platform.write.WriteExecutor;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Generic HTTP for documents: a header and its lines, read and written whole
 * (docs/ARCHITECTURE.md §23.8). One call registers everything:
 *
 * <pre>
 * documents.register(routes, "/sales/orders", Document.of(SalesOrder.DEF).collection(...).rules(...), "sales.order");
 *
 * QUERY  /sales/orders                   headers (EntityEndpoints.query)              .read
 * GET    /sales/orders/new               a blank document: defaults, no lines         .create
 * GET    /sales/orders/{id}              the header, its lines under each collection  .read
 * POST   /sales/orders                   the header's fields + "lines": [...]   201   .create
 * PATCH  /sales/orders/{id}              "version" + header fields + collections     .update
 * POST   /sales/orders/calculate         a new draft, recalculated, nothing saved     .create
 * POST   /sales/orders/{id}/calculate    a change to it, recalculated, nothing saved  .update
 * DELETE /sales/orders/{id}?version=N    soft delete of the header                    .delete
 * GET    /sales/orders/{id}/history      one entry per save, lines included           .read + audit.history.read
 * </pre>
 *
 * A save works on the whole document as it would be (Draft): the stored one
 * with the request laid over it. In a collection, a line with an "id" is
 * updated (only the fields sent), a line without one is added, a stored line
 * missing from the array is removed (soft delete); a collection left out of a
 * PATCH keeps its lines. Then DocumentRules.calculate fills the computed
 * values, the field checks (WriteBuilder) and DocumentRules.validate run, and
 * every problem is reported at once on paths like "lines[2].quantity".
 *
 * Writing: the header first, guarded by its version (409 VERSION_CONFLICT,
 * with the document as it is now in "current"), so one version covers the
 * document: a save that changes only lines still bumps it. One EntityChanged
 * per save, on the header; each collection's changes are in it under the
 * collection's name: {"added": [...], "changed": {"lineId": {field: {old,
 * new}}}, "removed": [ids]}. At most MAX_LINES lines per collection.
 */
public final class DocumentEndpoints {

  public static final int MAX_LINES = 1000;

  private static final TypeReference<Map<String, Object>> BODY_TYPE = new TypeReference<>() {
  };

  private final UnitOfWorkFactory uowFactory;
  private final PermissionChecker permissions;
  private final EventBus eventBus;
  private final Clock clock;
  private final ResourceCatalog catalog;
  private final EntityEndpoints entities;
  private final ErrorLocalizer localizer;
  private final QueryTranslator translator;
  private final QueryExecutor queries = new QueryExecutor();
  private final WriteBuilder writes = new WriteBuilder();
  private final WriteExecutor writer = new WriteExecutor();

  public DocumentEndpoints(UnitOfWorkFactory uowFactory, QueryLimits limits, PermissionChecker permissions,
      EventBus eventBus, Clock clock, ResourceCatalog catalog, Messages messages, EntityEndpoints entities) {
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.entities = Objects.requireNonNull(entities, "entities");
    this.localizer = new ErrorLocalizer(Objects.requireNonNull(messages, "messages"));
    // lines are read whole: one more than a document may have
    this.translator = new QueryTranslator(new QueryLimits(limits.maxDepth(), limits.maxConditions(),
        limits.maxSorts(), MAX_LINES + 1, limits.defaultPageSize(), limits.maxInList(), limits.maxRelationHops()));
  }

  /** Registers the routes above and the resource (with the document's rules) in the catalog. */
  public void register(Routes routes, String path, Document document, String permissionPrefix) {
    EntityDefinition<?> header = document.header();
    String read = Action.READ.permission(permissionPrefix);
    String create = Action.CREATE.permission(permissionPrefix);
    String update = Action.UPDATE.permission(permissionPrefix);
    for (String p : List.of(read, create, update)) {
      permissions.requireDeclared(p);
    }
    String one = path + "/{id}";
    routes.query(path, entities.query(header, read));
    routes.get(path + "/new", newDocument(document, create));
    routes.post(path + "/calculate", calculate(document, create, false));
    routes.get(one, get(document, read));
    routes.get(one + "/history", entities.history(header, read));
    routes.post(path, create(document, create));
    routes.patch(one, update(document, update));
    routes.post(one + "/calculate", calculate(document, update, true));
    Set<Action> actions = EnumSet.of(Action.READ, Action.CREATE, Action.UPDATE);
    if (header.softDeleteColumn() != null) {
      String delete = Action.DELETE.permission(permissionPrefix);
      routes.delete(one, entities.delete(header, delete));
      actions.add(Action.DELETE);
    }
    catalog.add(path, header, permissionPrefix, actions);
    if (document.rules() != null) {
      catalog.rules(header, document.rules());
    }
  }

  // ---- handlers ----

  private Handler get(Document document, String permission) {
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      long id = idParam(ctx, document);
      ctx.json(uowFactory.executeReadOnly(uow -> render(uow, document, id, rc)));
    };
  }

  private Handler newDocument(Document document, String permission) {
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      EntityDefinition<?> header = document.header();
      Map<String, Object> row = new LinkedHashMap<>();
      for (Field<?, ?> f : header.readableFields(access(rc))) {
        row.put(f.name(), f.defaultValue());
      }
      uowFactory.executeReadOnly(uow -> {
        if (document.rules() != null) {
          document.rules().defaults(uow, rc).forEach((field, value) -> {
            if (row.containsKey(field)) {
              row.put(field, value);
            }
          });
        }
        entities.attachReferences(uow, header, List.of(row), rc);
        return null;
      });
      Map<String, Object> out = withCollections(row, document, name -> List.of());
      out.put("_meta", Map.of("new", true));
      ctx.json(out);
    };
  }

  private Handler create(Document document, String permission) {
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      Map<String, Object> body = JsonBodies.read(ctx, BODY_TYPE.getType());
      Map<String, Object> created = uowFactory.execute(uow -> {
        Prepared p = prepare(uow, rc, document, null, body, true);
        p.throwIfInvalid();
        long id = save(uow, rc, document, p, null);
        return render(uow, document, id, rc);
      });
      ctx.status(201);
      ctx.header("Location", URI.create(ctx.path() + "/" + created.get(document.header().id().name())).toString());
      ctx.json(created);
    };
  }

  private Handler update(Document document, String permission) {
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      long id = idParam(ctx, document);
      Map<String, Object> body = JsonBodies.read(ctx, BODY_TYPE.getType());
      try {
        ctx.json(uowFactory.execute(uow -> {
          Prepared p = prepare(uow, rc, document, id, body, true);
          Set<String> changed = new LinkedHashSet<>(p.parsed().header().keySet());
          changed.addAll(p.parsed().lines().keySet());
          entities.enforceRules(uow, document.header(), id, changed, rc, false);
          p.throwIfInvalid();
          save(uow, rc, document, p, id);
          return render(uow, document, id, rc);
        }));
      } catch (ErpException e) {
        if ("VERSION_CONFLICT".equals(e.code()) && e.current() == null) {
          uowFactory.executeReadOnly(uow -> {
            try {
              e.withCurrent(render(uow, document, id, rc));
            } catch (ResourceNotFoundException gone) {
              // deleted meanwhile: no current
            }
            return null;
          });
        }
        throw e;
      }
    };
  }

  /**
   * The document recalculated as it would be saved, nothing written:
   * {"document": {...}, "warnings": [...], "errors": [...]}, the errors being
   * what a save would report (translated, like the warnings).
   */
  private Handler calculate(Document document, String permission, boolean existing) {
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      Long id = existing ? idParam(ctx, document) : null;
      Map<String, Object> body = ctx.body().isBlank() ? Map.of() : JsonBodies.read(ctx, BODY_TYPE.getType());
      ctx.json(uowFactory.executeReadOnly(uow -> {
        Prepared p = prepare(uow, rc, document, id, body, false);
        FieldAccess access = access(rc);
        EntityDefinition<?> header = document.header();
        Map<String, Object> row = readable(header, p.draft().header(), access);
        entities.attachReferences(uow, header, List.of(row), rc);
        Map<String, Object> doc = withCollections(row, document, name -> {
          Document.Collection c = collection(document, name);
          List<Map<String, Object>> lines = new ArrayList<>();
          for (Map<String, Object> line : p.draft().lines(name)) {
            lines.add(readable(c.child(), line, access));
          }
          entities.attachReferences(uow, c.child(), lines, rc);
          return lines;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", doc);
        out.put("warnings", localizer.localize(p.draft().warnings(), rc.language()));
        out.put("errors", localizer.localize(p.errors(), rc.language()));
        return out;
      }));
    };
  }

  // ---- the pipeline: parse, merge, calculate, check ----

  /** The request's parts: the header's fields, each collection's lines (absent: left out), the version. */
  private record Parsed(Long version, Map<String, Object> header, Map<String, List<Map<String, Object>>> lines) {
  }

  /**
   * One line of a collection in the draft: new (lineId null) or stored, with
   * what the client sent; {@code unknown}: an id that isn't a line of this
   * document (reported; in the draft, never written).
   */
  private record LinePlan(int index, Long lineId, Long storedVersion, Map<String, Object> client,
      Map<String, Object> stored, boolean unknown) {
  }

  private record Prepared(Parsed parsed, Draft draft, Map<String, Object> storedHeader,
      Map<String, List<LinePlan>> plans, Map<String, List<Long>> removed, List<FieldError> errors) {
    void throwIfInvalid() {
      if (!errors.isEmpty()) {
        throw new ValidationFailedException("Validation failed", errors);
      }
    }
  }

  /**
   * @param saving a save, which needs the version and something to change; a
   *               calculation of a stored document may send nothing
   */
  private Prepared prepare(UnitOfWork uow, RequestContext rc, Document document, Long id, Map<String, Object> body,
      boolean saving) {
    EntityDefinition<?> header = document.header();
    List<FieldError> errors = new ArrayList<>();
    Parsed parsed = parse(document, body, id != null, saving, errors);

    Map<String, Object> storedHeader = id == null ? Map.of() : stored(uow, header, id, rc)
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", header.name() + " not found"));
    Map<String, Object> merged = new LinkedHashMap<>(storedHeader);
    parsed.header().forEach((name, raw) -> header.field(name).ifPresent(f -> merged.put(name, lenient(f, raw))));

    Map<String, List<Map<String, Object>>> lines = new LinkedHashMap<>();
    Map<String, List<LinePlan>> plans = new LinkedHashMap<>();
    Map<String, List<Long>> removed = new LinkedHashMap<>();
    for (Document.Collection c : document.collections()) {
      List<Map<String, Object>> storedLines = id == null ? List.of() : storedLines(uow, c, id, rc);
      Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
      storedLines.forEach(l -> byId.put(((Number) l.get(c.child().id().name())).longValue(), l));
      List<Map<String, Object>> draftLines = new ArrayList<>();
      List<LinePlan> plan = new ArrayList<>();
      List<Map<String, Object>> client = parsed.lines().get(c.name());
      if (client == null) {
        for (Map<String, Object> l : storedLines) {
          draftLines.add(new LinkedHashMap<>(l));
          plan.add(new LinePlan(plan.size(), lineId(c, l), version(c, l), Map.of(), l, false));
        }
      } else {
        Set<Long> kept = new LinkedHashSet<>();
        for (int i = 0; i < client.size(); i++) {
          Map<String, Object> line = new LinkedHashMap<>(client.get(i));
          Object rawId = line.remove(c.child().id().name());
          Map<String, Object> draftLine = new LinkedHashMap<>();
          Long lineId = null;
          Map<String, Object> stored = Map.of();
          boolean unknown = false;
          if (rawId != null) {
            lineId = lenient(c.child().id(), rawId) instanceof Long l ? l : null;
            if (lineId == null || !byId.containsKey(lineId) || !kept.add(lineId)) {
              errors.add(new FieldError(c.name() + "[" + i + "].id", "UNKNOWN_LINE",
                  "is not a line of this document (or is given twice)"));
              lineId = null;
              unknown = true;
            } else {
              stored = byId.get(lineId);
              draftLine.putAll(stored);
            }
          }
          line.forEach((name, raw) -> c.child().field(name).ifPresent(f -> draftLine.put(name, lenient(f, raw))));
          draftLines.add(draftLine);
          plan.add(new LinePlan(i, lineId, lineId == null ? null : version(c, stored), line, stored, unknown));
        }
        List<Long> gone = new ArrayList<>(byId.keySet());
        gone.removeAll(kept);
        removed.put(c.name(), gone);
      }
      lines.put(c.name(), draftLines);
      plans.put(c.name(), plan);
    }

    Draft draft = new Draft(id, merged, lines);
    DocumentRules rules = document.rules();
    if (rules != null) {
      rules.calculate(uow, rc, draft);
    }
    // what a save would build: every field problem, on its path
    WriteContext wc = writeContext(rc);
    headerWrite(document, id, parsed, draft, storedHeader, wc, rc, errors, saving);
    for (Document.Collection c : document.collections()) {
      for (LinePlan line : plans.get(c.name())) {
        lineWrite(c, line, draft, id == null ? 0L : id, wc, rc, errors);
      }
    }
    if (rules != null) {
      rules.validate(uow, rc, draft, Validation.into(errors));
    }
    return new Prepared(parsed, draft, storedHeader, plans, removed, errors);
  }

  private Parsed parse(Document document, Map<String, Object> body, boolean updating, boolean saving,
      List<FieldError> errors) {
    EntityDefinition<?> header = document.header();
    String versionName = header.versionField() == null ? null : header.versionField().name();
    Long version = null;
    Map<String, Object> fields = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> lines = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : body.entrySet()) {
      String key = e.getKey();
      Optional<Document.Collection> collection = document.collections().stream()
          .filter(c -> c.name().equals(key)).findFirst();
      if (updating && key.equals(versionName)) {
        version = lenient(header.versionField(), e.getValue()) instanceof Long v ? v : null;
        if (version == null) {
          errors.add(new FieldError(key, "INVALID_VALUE", "must be an integer"));
        }
      } else if (collection.isPresent()) {
        lines.put(key, lines(key, e.getValue(), errors));
      } else {
        fields.put(key, e.getValue());
      }
    }
    if (saving && updating && versionName != null && version == null && !body.containsKey(versionName)) {
      errors.add(new FieldError(versionName, "REQUIRED", "the version you last read is required"));
    }
    if (saving && updating && fields.isEmpty() && lines.isEmpty()) {
      errors.add(new FieldError("", "EMPTY_PATCH", "at least one field or collection must be provided"));
    }
    return new Parsed(version, fields, lines);
  }

  private static List<Map<String, Object>> lines(String name, Object raw, List<FieldError> errors) {
    if (!(raw instanceof List<?> list)) {
      errors.add(new FieldError(name, "INVALID_TYPE", "must be an array of lines"));
      return List.of();
    }
    if (list.size() > MAX_LINES) {
      errors.add(new FieldError(name, "TOO_MANY_LINES", "at most " + MAX_LINES + " lines",
          Map.of("max", MAX_LINES)));
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i) instanceof Map<?, ?> m) {
        Map<String, Object> line = new LinkedHashMap<>();
        m.forEach((k, v) -> line.put(String.valueOf(k), v));
        out.add(line);
      } else {
        errors.add(new FieldError(name + "[" + i + "]", "INVALID_TYPE", "must be an object"));
        out.add(new LinkedHashMap<>());
      }
    }
    return out;
  }

  /** The header's write; null when nothing about the header changes (a save of lines only: a touch). */
  private SQLWrite<?> headerWrite(Document document, Long id, Parsed parsed, Draft draft,
      Map<String, Object> storedHeader, WriteContext wc, RequestContext rc, List<FieldError> errors,
      boolean saving) {
    EntityDefinition<?> header = document.header();
    Map<String, Object> computed = changedOnly(draft.computedHeader(), storedHeader, parsed.header().keySet());
    // a missing version is reported by parse() on a save; a calculation checks the fields with any version
    Long version = parsed.version() != null || !saving ? (parsed.version() == null ? 0L : parsed.version()) : null;
    try {
      if (id == null) {
        return writes.insert(header, parsed.header(), wc, access(rc), draft.computedHeader());
      }
      if ((parsed.header().isEmpty() && computed.isEmpty()) || (header.versionField() != null && version == null)) {
        return null;
      }
      return writes.update(header, id, version, parsed.header(), wc, access(rc), computed);
    } catch (ValidationFailedException e) {
      errors.addAll(e.fieldErrors());
      return null;
    }
  }

  /** A line's write: an insert, an update (only what changes), or null when nothing changes. */
  private SQLWrite<?> lineWrite(Document.Collection c, LinePlan line, Draft draft, long headerId, WriteContext wc,
      RequestContext rc, List<FieldError> errors) {
    if (line.unknown()) {
      return null;
    }
    Map<String, Object> computed = draft.computedLine(c.name(), line.index());
    String at = c.name() + "[" + line.index() + "]";
    try {
      if (line.lineId() == null) {
        Map<String, Object> server = new LinkedHashMap<>(computed);
        server.put(c.parentField().name(), headerId);
        return writes.insert(c.child(), line.client(), wc, access(rc), server);
      }
      Map<String, Object> changedComputed = changedOnly(computed, line.stored(), line.client().keySet());
      if (line.client().isEmpty() && changedComputed.isEmpty()) {
        return null;
      }
      return writes.update(c.child(), line.lineId(), line.storedVersion(), line.client(), wc, access(rc),
          changedComputed);
    } catch (ValidationFailedException e) {
      e.fieldErrors().forEach(fe -> errors.add(prefixed(at, fe)));
      return null;
    }
  }

  /** Computed values that differ from the stored ones (and that the client didn't send itself). */
  private static Map<String, Object> changedOnly(Map<String, Object> computed, Map<String, Object> stored,
      Set<String> sentByClient) {
    Map<String, Object> out = new LinkedHashMap<>();
    computed.forEach((name, value) -> {
      if (!sentByClient.contains(name) && !same(value, stored.get(name))) {
        out.put(name, value);
      }
    });
    return out;
  }

  private static boolean same(Object a, Object b) {
    if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
      return x.compareTo(y) == 0;
    }
    return Objects.equals(a, b);
  }

  // ---- saving ----

  /** Writes the prepared document (already checked); returns its id. */
  private long save(UnitOfWork uow, RequestContext rc, Document document, Prepared p, Long id) {
    EntityDefinition<?> header = document.header();
    WriteContext wc = writeContext(rc);
    List<FieldError> errors = new ArrayList<>();
    Map<String, FieldChange> changes = new LinkedHashMap<>();
    Set<String> changedFields = new LinkedHashSet<>();
    long docId;

    // 1. the header first: its version guards the whole document
    SQLWrite<?> headerWrite = headerWrite(document, id, p.parsed(), p.draft(), p.storedHeader(), wc, rc, errors,
        true);
    if (id == null) {
      docId = writer.insert(uow, headerWrite, rc.companyId());
      changes.putAll(created(headerWrite));
    } else {
      docId = id;
      SQLWrite<?> write = headerWrite != null ? headerWrite
          : writes.touch(header, id, p.parsed().version(), wc);
      Map<String, FieldChange> updated = updated(uow, write, id, rc);
      changes.putAll(updated);
      changedFields.addAll(updated.keySet());
    }

    // 2. each collection: removed, changed, added lines
    for (Document.Collection c : document.collections()) {
      Map<String, Object> summary = new LinkedHashMap<>();
      List<Long> removed = p.removed().getOrDefault(c.name(), List.of());
      for (Long lineId : removed) {
        Long version = p.plans().get(c.name()).stream().filter(l -> lineId.equals(l.lineId())).findFirst()
            .map(LinePlan::storedVersion).orElse(storedVersion(uow, c, lineId, rc));
        writer.update(uow, writes.softDelete(c.child(), lineId, version, wc), lineId, rc.companyId());
      }
      Map<String, Object> changedLines = new LinkedHashMap<>();
      List<Map<String, Object>> added = new ArrayList<>();
      for (LinePlan line : p.plans().get(c.name())) {
        SQLWrite<?> write = lineWrite(c, line, p.draft(), docId, wc, rc, errors);
        if (write == null) {
          continue;
        }
        String at = c.name() + "[" + line.index() + "]";
        try {
          if (line.lineId() == null) {
            long lineId = writer.insert(uow, write, rc.companyId());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(c.child().id().name(), lineId);
            write.values().forEach((f, v) -> values.put(f.name(), v));
            added.add(values);
          } else {
            Map<String, FieldChange> lineChanges = updated(uow, write, line.lineId(), rc);
            if (!lineChanges.isEmpty()) {
              changedLines.put(String.valueOf(line.lineId()), asOldNew(lineChanges));
            }
          }
        } catch (ValidationFailedException e) {
          e.fieldErrors().forEach(fe -> errors.add(prefixed(at, fe)));
        }
      }
      if (!added.isEmpty()) {
        summary.put("added", added);
      }
      if (!changedLines.isEmpty()) {
        summary.put("changed", changedLines);
      }
      if (!removed.isEmpty()) {
        summary.put("removed", removed);
      }
      if (!summary.isEmpty()) {
        changes.put(c.name(), new FieldChange(null, summary));
        changedFields.add(c.name());
      }
    }
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", errors); // database checks (unique, references)
    }

    // 3. one event for the whole save
    if (id == null) {
      eventBus.publish(uow, new EntityChanged.Created(header.name(), docId, rc.companyId(), rc.userId(),
          rc.requestId(), wc.now(), changes));
    } else {
      eventBus.publish(uow, new EntityChanged.Updated(header.name(), docId, rc.companyId(), rc.userId(),
          rc.requestId(), wc.now(), changedFields, changes));
    }
    return docId;
  }

  /** A line's changes as the audit trail writes a record's: {field: {"old", "new"}}. */
  private static Map<String, Object> asOldNew(Map<String, FieldChange> changes) {
    Map<String, Object> out = new LinkedHashMap<>();
    changes.forEach((field, change) -> {
      Map<String, Object> pair = new LinkedHashMap<>();
      pair.put("old", change.oldValue());
      pair.put("new", change.newValue());
      out.put(field, pair);
    });
    return out;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private Map<String, FieldChange> updated(UnitOfWork uow, SQLWrite write, long id, RequestContext rc) {
    Map before = writer.update(uow, write, id, rc.companyId());
    return FieldChanges.updated(before, write.values());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Map<String, FieldChange> created(SQLWrite write) {
    return FieldChanges.created(write.values());
  }

  // ---- reading ----

  /** GET's shape: the header (with _refs and _meta), then each collection's lines (with _refs). */
  private Map<String, Object> render(UnitOfWork uow, Document document, long id, RequestContext rc) {
    Map<String, Object> row = entities.row(uow, document.header(), id, rc)
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", document.header().name() + " not found"));
    return withCollections(row, document, name -> {
      Document.Collection c = collection(document, name);
      List<Map<String, Object>> lines = queries.listAsMaps(uow,
          translator.translate(c.child(), byParent(c, id), rc.companyId(), access(rc)));
      entities.attachReferences(uow, c.child(), lines, rc);
      return lines;
    });
  }

  /** The header's fields, then the collections, then _refs / _meta. */
  private static Map<String, Object> withCollections(Map<String, Object> row, Document document,
      Function<String, List<Map<String, Object>>> lines) {
    Map<String, Object> out = new LinkedHashMap<>();
    row.forEach((k, v) -> {
      if (!k.startsWith("_")) {
        out.put(k, v);
      }
    });
    for (Document.Collection c : document.collections()) {
      out.put(c.name(), lines.apply(c.name()));
    }
    row.forEach((k, v) -> {
      if (k.startsWith("_")) {
        out.put(k, v);
      }
    });
    return out;
  }

  /** The stored header, every field (rules may use any): empty if no such live row in the company. */
  private Optional<Map<String, Object>> stored(UnitOfWork uow, EntityDefinition<?> header, long id,
      RequestContext rc) {
    QuerySpec byId = new QuerySpec(new FilterNode.Condition(header.id().name(), Operator.EQUALS, id),
        List.of(), 1, 0);
    return queries.listAsMaps(uow, translator.translate(header, byId, rc.companyId(), FieldAccess.ALLOW_ALL))
        .stream().findFirst();
  }

  private List<Map<String, Object>> storedLines(UnitOfWork uow, Document.Collection c, long id, RequestContext rc) {
    return queries.listAsMaps(uow, translator.translate(c.child(), byParent(c, id), rc.companyId(),
        FieldAccess.ALLOW_ALL));
  }

  private Long storedVersion(UnitOfWork uow, Document.Collection c, long lineId, RequestContext rc) {
    QuerySpec byId = new QuerySpec(new FilterNode.Condition(c.child().id().name(), Operator.EQUALS, lineId),
        List.of(), 1, 0);
    return queries.listAsMaps(uow, translator.translate(c.child(), byId, rc.companyId(), FieldAccess.ALLOW_ALL))
        .stream().findFirst().map(l -> version(c, l)).orElse(null);
  }

  private static QuerySpec byParent(Document.Collection c, long id) {
    return new QuerySpec(new FilterNode.Condition(c.parentField().name(), Operator.EQUALS, id), List.of(),
        MAX_LINES, 0);
  }

  private static Long lineId(Document.Collection c, Map<String, Object> line) {
    return ((Number) line.get(c.child().id().name())).longValue();
  }

  private static Long version(Document.Collection c, Map<String, Object> line) {
    return c.child().versionField() == null ? null
        : line.get(c.child().versionField().name()) instanceof Number n ? n.longValue() : null;
  }

  // ---- helpers ----

  private static Document.Collection collection(Document document, String name) {
    return document.collections().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  /** The value typed, or null when the client sent it badly (the checks report it). */
  private static Object lenient(Field<?, ?> f, Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return f.type().coerce(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The fields of a draft row the caller may read. */
  private static Map<String, Object> readable(EntityDefinition<?> entity, Map<String, Object> values,
      FieldAccess access) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Field<?, ?> f : entity.readableFields(access)) {
      out.put(f.name(), values.get(f.name()));
    }
    return out;
  }

  private static FieldError prefixed(String at, FieldError fe) {
    String field = fe.field().isEmpty() ? at : at + "." + fe.field();
    return new FieldError(field, fe.code(), fe.message(), fe.params());
  }

  private RequestContext authorize(Context ctx, String permission) {
    RequestContext rc = RequestContexts.require(ctx);
    permissions.require(rc, permission);
    return rc;
  }

  private static FieldAccess access(RequestContext rc) {
    return FieldAccess.forPermissions(rc::hasPermission);
  }

  private WriteContext writeContext(RequestContext rc) {
    return new WriteContext(rc.companyId(), rc.userId(), clock.instant());
  }

  private static long idParam(Context ctx, Document document) {
    try {
      long id = Long.parseLong(ctx.pathParam("id"));
      if (id > 0) {
        return id;
      }
    } catch (NumberFormatException ignored) {
      // fall through
    }
    throw new ResourceNotFoundException("NOT_FOUND", document.header().name() + " not found");
  }
}

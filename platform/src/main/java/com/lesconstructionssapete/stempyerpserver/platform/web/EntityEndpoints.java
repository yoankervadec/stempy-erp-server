package com.lesconstructionssapete.stempyerpserver.platform.web;

import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;
import com.lesconstructionssapete.stempyerpserver.platform.web.ResourceCatalog.Resource;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditHistory;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditRecord;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Operator;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.LabelKeys;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.query.FilterNode;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryExecutor;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.query.QuerySpec;
import com.lesconstructionssapete.stempyerpserver.platform.query.QuerySpecParser;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.query.SQLQuery;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged;
import com.lesconstructionssapete.stempyerpserver.platform.write.FieldChanges;
import com.lesconstructionssapete.stempyerpserver.platform.write.SQLWrite;
import com.lesconstructionssapete.stempyerpserver.platform.write.WriteBuilder;
import com.lesconstructionssapete.stempyerpserver.platform.write.WriteContext;
import com.lesconstructionssapete.stempyerpserver.platform.write.WriteExecutor;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Generic HTTP endpoints driven by an EntityDefinition alone, so a module gets
 * working routes for an entity in one line:
 *
 * <pre>
 * crud.register(routes, "/masterdata/products", Product.DEF, "masterdata.product");
 *
 * QUERY  /masterdata/products        search (QuerySpecParser body)    masterdata.product.read    200 page, hasMore
 * GET    /masterdata/products/{id}   one row                          masterdata.product.read    200 row
 * POST   /masterdata/products        create { field: value }          masterdata.product.create  201 row
 * PATCH  /masterdata/products/{id}   update { version, field: value } masterdata.product.update  200 row
 * DELETE /masterdata/products/{id}?version=N   soft delete            masterdata.product.delete  204
 * </pre>
 *
 * Rules every route follows:
 * - The caller must hold the route's permission (403 PERMISSION_DENIED).
 * - Everything is scoped to the caller's company and hides soft-deleted rows;
 * company_id and the audit columns come from the request, never the body.
 * - Only fields the caller may read are shown, filtered on or accepted; only
 * fields they may write are accepted (FieldAccess).
 * - Bodies are validated BEFORE a connection is taken (422 with every field
 * error); database checks (unique values, same-company references) run in the
 * transaction (WriteExecutor).
 * - Updates and deletes carry the version the client last read: 409
 * VERSION_CONFLICT if someone changed the row since.
 * - Every write publishes an EntityChanged event in its transaction.
 * - Writes answer with the row as the caller now sees it.
 *
 * A platform service built once by ModuleHost and published in the
 * ServiceRegistry; modules resolve it in registerRoutes(). Entities whose
 * writes need business rules get a hand-written controller and orchestrator
 * instead, and register only READ here.
 */
public final class EntityEndpoints {

  /** What a route set allows; the permission is "{prefix}.{action}". */
  public enum Action {
    READ, CREATE, UPDATE, DELETE;

    public String permission(String prefix) {
      return prefix + "." + name().toLowerCase(Locale.ROOT);
    }
  }

  /** The QUERY response body. */
  public record Page(List<Map<String, Object>> data, PageInfo page) {
  }

  /** @param hasMore another page follows (one row more than the limit was there) */
  public record PageInfo(int limit, int offset, boolean hasMore) {
  }

  /** The first {@code limit} of {@code rows} read with one row of look-ahead, and whether there were more. */
  private static Page page(List<Map<String, Object>> rows, int limit, int offset) {
    boolean more = rows.size() > limit;
    return new Page(more ? new ArrayList<>(rows.subList(0, limit)) : rows, new PageInfo(limit, offset, more));
  }

  private static final TypeReference<Map<String, Object>> BODY_TYPE = new TypeReference<>() {
  };

  private final UnitOfWorkFactory uowFactory;
  private final QuerySpecParser parser;
  private final QueryTranslator translator;
  private final QueryExecutor queries = new QueryExecutor();
  private final WriteBuilder writes = new WriteBuilder();
  private final WriteExecutor writer = new WriteExecutor();
  private final PermissionChecker permissions;
  private final EventBus eventBus;
  private final Clock clock;
  private final QueryLimits limits;
  private final AuditLog audit;
  private final Supplier<Optional<UserDirectory>> userDirectory;
  private final ResourceCatalog catalog;
  private final Messages messages;
  private final ReferenceTitles references;
  private final RecordMeta recordMeta;

  /**
   * @param clock         should tick in whole seconds, like a MySQL TIMESTAMP
   * @param userDirectory names the users in histories; looked up per request
   *                      (the users module publishes it after this is built),
   *                      empty: histories show user ids only
   * @param catalog       where register() and declareWrite() record the
   *                      resources GET /meta describes
   * @param messages      labels for histories (field names, actions)
   */
  public EntityEndpoints(UnitOfWorkFactory uowFactory, QueryLimits limits, PermissionChecker permissions,
      EventBus eventBus, Clock clock, AuditLog audit, Supplier<Optional<UserDirectory>> userDirectory,
      ResourceCatalog catalog, Messages messages) {
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.userDirectory = Objects.requireNonNull(userDirectory, "userDirectory");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.references = new ReferenceTitles(catalog, permissions);
    this.recordMeta = new RecordMeta(permissions, messages);
    this.parser = new QuerySpecParser(limits);
    this.translator = new QueryTranslator(limits);
  }

  /**
   * Registers the routes for {@code actions} (all of them if none given) and
   * records the entity as a resource in the ResourceCatalog. Fails at startup
   * if a needed permission isn't declared, DELETE is asked for an entity
   * without soft delete, or the entity is already registered.
   */
  public <E> void register(Routes routes, String path, EntityDefinition<E> entity, String permissionPrefix,
      Action... actions) {
    Set<Action> allowed = actions.length == 0 ? EnumSet.allOf(Action.class) : EnumSet.of(actions[0], actions);
    String one = path + "/{id}";
    if (allowed.contains(Action.READ)) {
      routes.query(path, query(entity, Action.READ.permission(permissionPrefix)));
      routes.get(path + "/new", newRecord(entity)); // before {id}, so "new" is never taken for an id
      routes.get(one, get(entity, Action.READ.permission(permissionPrefix)));
      routes.get(one + "/history", history(entity, Action.READ.permission(permissionPrefix)));
    }
    if (allowed.contains(Action.CREATE)) {
      routes.post(path, create(entity, Action.CREATE.permission(permissionPrefix)));
    }
    if (allowed.contains(Action.UPDATE)) {
      routes.patch(one, update(entity, Action.UPDATE.permission(permissionPrefix)));
    }
    if (allowed.contains(Action.DELETE)) {
      if (entity.softDeleteColumn() == null) {
        throw new IllegalStateException(entity.name() + " has no soft delete; DELETE is not supported");
      }
      routes.delete(one, delete(entity, Action.DELETE.permission(permissionPrefix)));
    }
    catalog.add(path, entity, permissionPrefix, allowed);
  }

  /**
   * Tells the catalog that a hand-written route serves {@code action} for an
   * entity registered here (at least READ), with the same body shape as the
   * generic one and on the same path, so the generic UI form works for it.
   * E.g. accounts: POST and PATCH /gl/accounts need gl.account.manage.
   */
  public void declareWrite(EntityDefinition<?> entity, Action action, String permission) {
    permissions.requireDeclared(permission);
    catalog.declareWrite(entity, action, permission);
  }

  /**
   * What a record's state allows (locked fields, no update / delete) and the
   * defaults of a new one, for an entity registered here: shown in "_meta" and
   * GET {path}/new (docs/ARCHITECTURE.md §23.9).
   */
  public void rules(EntityDefinition<?> entity, RecordRules rules) {
    catalog.rules(entity, rules);
  }

  /**
   * Describes an action of an entity registered here, for GET /meta and the
   * records' "_meta". The module registers its route itself.
   */
  public void action(EntityDefinition<?> entity, ResourceAction action) {
    action.check();
    permissions.requireDeclared(action.permission());
    catalog.action(entity, action);
  }

  // ---- handlers (public for routes that need a custom path) ----

  /**
   * QUERY: search, filter, sort, page. An empty body means "first page,
   * default order". Each row has its references' titles (_refs); the page says
   * whether another follows (hasMore).
   */
  public <E> Handler query(EntityDefinition<E> entity, String permission) {
    permissions.requireDeclared(permission);
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      QuerySpec spec = parser.parse(ctx.body().isBlank() ? Map.of() : JsonBodies.read(ctx, BODY_TYPE.getType()));
      SQLQuery<E> query = translator.translate(entity, spec, rc.companyId(), access(rc), true);
      Page page = uowFactory.executeReadOnly(uow -> {
        Page p = page(queries.listAsMaps(uow, query), spec.limit(), spec.offset());
        references.attach(uow, entity, p.data(), rc);
        return p;
      });
      ctx.json(page);
    };
  }

  public <E> Handler get(EntityDefinition<E> entity, String permission) {
    permissions.requireDeclared(permission);
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      long id = idParam(ctx, entity);
      ctx.json(uowFactory.executeReadOnly(uow -> row(uow, entity, id, rc))
          .orElseThrow(() -> notFound(entity)));
    };
  }

  /**
   * GET {path}/new: a blank record for a create form (docs/ARCHITECTURE.md
   * §23.9), shaped like GET: every readable field, at its declared default
   * (Field.defaultValue) or the RecordRules' defaults, else null; _refs for the
   * defaulted references; "_meta": {"new": true}. Needs the resource's create
   * permission (a generic or a declared write); 404 if it has none.
   */
  public <E> Handler newRecord(EntityDefinition<E> entity) {
    return ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      Resource r = catalog.forEntity(entity).orElseThrow(() -> notFound(entity));
      permissions.require(rc, r.permission(Action.CREATE).orElseThrow(() -> notFound(entity)));
      Map<String, Object> row = new LinkedHashMap<>();
      for (Field<E, ?> f : entity.readableFields(access(rc))) {
        row.put(f.name(), f.defaultValue());
      }
      uowFactory.executeReadOnly(uow -> {
        if (r.rules() != null) {
          r.rules().defaults(uow, rc).forEach((field, value) -> {
            if (row.containsKey(field)) {
              row.put(field, value);
            }
          });
        }
        references.attach(uow, entity, List.of(row), rc);
        return null;
      });
      row.put("_meta", Map.of("new", true));
      ctx.json(row);
    };
  }

  public <E> Handler create(EntityDefinition<E> entity, String permission) {
    permissions.requireDeclared(permission);
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      WriteContext wc = writeContext(rc);
      SQLWrite<E> insert = writes.insert(entity, JsonBodies.read(ctx, BODY_TYPE.getType()), wc, access(rc));
      Map<String, Object> created = uowFactory.execute(uow -> {
        long id = writer.insert(uow, insert, rc.companyId());
        eventBus.publish(uow, new EntityChanged.Created(entity.name(), id, rc.companyId(), rc.userId(),
            rc.requestId(), wc.now(), FieldChanges.created(insert.values())));
        return row(uow, entity, id, rc).orElseThrow(() -> notFound(entity));
      });
      ctx.status(201);
      ctx.header("Location", URI.create(ctx.path() + "/" + created.get(entity.id().name())).toString());
      ctx.json(created);
    };
  }

  public <E> Handler update(EntityDefinition<E> entity, String permission) {
    permissions.requireDeclared(permission);
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      long id = idParam(ctx, entity);
      Map<String, Object> patch = JsonBodies.read(ctx, BODY_TYPE.getType());
      Long version = entity.versionField() == null ? null : version(patch.remove(entity.versionField().name()));
      WriteContext wc = writeContext(rc);
      SQLWrite<E> update = writes.update(entity, id, version, patch, wc, access(rc));
      Set<String> changed = update.values().keySet().stream().map(f -> f.name())
          .collect(java.util.stream.Collectors.toSet());
      try {
        ctx.json(uowFactory.execute(uow -> {
          var before = writer.update(uow, update, id, rc.companyId());
          eventBus.publish(uow, new EntityChanged.Updated(entity.name(), id, rc.companyId(), rc.userId(),
              rc.requestId(), wc.now(), changed, FieldChanges.updated(before, update.values())));
          return row(uow, entity, id, rc).orElseThrow(() -> notFound(entity));
        }));
      } catch (ErpException e) {
        throw withCurrent(e, rc, entity, id);
      }
    };
  }

  public <E> Handler delete(EntityDefinition<E> entity, String permission) {
    permissions.requireDeclared(permission);
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      long id = idParam(ctx, entity);
      Long version = entity.versionField() == null ? null : version(ctx.queryParam("version"));
      WriteContext wc = writeContext(rc);
      SQLWrite<E> delete = writes.softDelete(entity, id, version, wc);
      try {
        uowFactory.run(uow -> {
          writer.update(uow, delete, id, rc.companyId());
          eventBus.publish(uow, new EntityChanged.Deleted(entity.name(), id, rc.companyId(), rc.userId(),
              rc.requestId(), wc.now()));
        });
      } catch (ErpException e) {
        throw withCurrent(e, rc, entity, id);
      }
      ctx.status(204);
    };
  }

  /**
   * GET {id}/history: the record's audit entries in the caller's company,
   * newest first, paged with ?limit= and ?offset=. Needs the record's read
   * permission AND audit.history.read; values of fields the caller may not read are
   * masked. Each item has "actionLabel" and "labels" (its fields' labels) in
   * the reader's language. Works for deleted rows too (their history outlives them).
   */
  public <E> Handler history(EntityDefinition<E> entity, String readPermission) {
    permissions.requireDeclared(readPermission);
    permissions.requireDeclared(AuditLog.READ_PERMISSION);
    return ctx -> {
      RequestContext rc = authorize(ctx, readPermission);
      permissions.require(rc, AuditLog.READ_PERMISSION);
      long id = idParam(ctx, entity);
      int limit = intParam(ctx, "limit", limits.defaultPageSize(), 1, limits.maxPageSize());
      int offset = intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE);
      Long company = entity.companyColumn() == null ? null : rc.companyId();
      List<Map<String, Object>> items = uowFactory.executeReadOnly(uow -> {
        List<AuditRecord> records = audit.history(uow, entity.name(), id, company, limit + 1, offset);
        Set<Long> userIds = records.stream().map(AuditRecord::userId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, UserDirectory.UserSummary> users = userIds.isEmpty() ? Map.of()
            : userDirectory.get().map(d -> d.find(uow, userIds)).orElse(Map.of());
        return AuditHistory.view(records, entity, access(rc), users, labels(entity, rc.language()));
      });
      ctx.json(page(items, limit, offset));
    };
  }

  /**
   * One row as {@code rc} sees it (same company, live, readable fields only),
   * in its own read-only transaction: what GET returns. For hand-written
   * writes, so their responses have the same shape as the generic routes'.
   * Checks no permission: the caller already did.
   */
  public <E> Optional<Map<String, Object>> read(RequestContext rc, EntityDefinition<E> entity, long id) {
    return uowFactory.executeReadOnly(uow -> row(uow, entity, id, rc));
  }

  /**
   * For a 409 VERSION_CONFLICT: attaches the record as the caller now sees it
   * ("current" in the response), read in a transaction of its own after the
   * failed one; any other error is returned as is. For hand-written writes too:
   * {@code catch (ErpException e) { throw entities.withCurrent(e, rc, Account.DEF, id); }}.
   */
  public ErpException withCurrent(ErpException e, RequestContext rc, EntityDefinition<?> entity, long id) {
    if (VERSION_CONFLICT.equals(e.code()) && e.current() == null) {
      read(rc, entity, id).ifPresent(e::withCurrent);
    }
    return e;
  }

  private static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  // ---- internals ----

  private RequestContext authorize(Context ctx, String permission) {
    RequestContext rc = RequestContexts.require(ctx);
    permissions.require(rc, permission);
    return rc;
  }

  /** Field and action labels for a history, in the reader's language. */
  private AuditHistory.Labels labels(EntityDefinition<?> entity, Language language) {
    return new AuditHistory.Labels() {
      @Override
      public String field(String name) {
        return messages.label(language, name, LabelKeys.field(entity.name(), name));
      }

      @Override
      public String action(String action) {
        return messages.label(language, action, LabelKeys.auditAction(action));
      }
    };
  }

  private static FieldAccess access(RequestContext rc) {
    return FieldAccess.forPermissions(rc::hasPermission);
  }

  private WriteContext writeContext(RequestContext rc) {
    return new WriteContext(rc.companyId(), rc.userId(), clock.instant());
  }

  /**
   * The row as this caller sees it (same company, live, readable fields only),
   * with its references' titles (_refs) and what the caller may do with it
   * (_meta, when the entity is a resource).
   */
  private <E> Optional<Map<String, Object>> row(UnitOfWork uow, EntityDefinition<E> entity, long id,
      RequestContext rc) {
    QuerySpec byId = new QuerySpec(new FilterNode.Condition(entity.id().name(), Operator.EQUALS, id),
        List.of(), 1, 0);
    SQLQuery<E> query = translator.translate(entity, byId, rc.companyId(), access(rc));
    List<Map<String, Object>> rows = queries.listAsMaps(uow, query);
    references.attach(uow, entity, rows, rc);
    Optional<Resource> resource = catalog.forEntity(entity);
    for (Map<String, Object> row : rows) {
      resource.ifPresent(r -> row.put("_meta", recordMeta.describe(uow, r, fieldsOnly(row), rc)));
    }
    return rows.stream().findFirst();
  }

  /** The row without _refs / _meta: what rules and confirmation texts see. */
  private static Map<String, Object> fieldsOnly(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>(row);
    out.keySet().removeIf(k -> k.startsWith("_"));
    return out;
  }

  private static int intParam(Context ctx, String name, int fallback, int min, int max) {
    String raw = ctx.queryParam(name);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      int value = Integer.parseInt(raw.strip());
      if (value >= min && value <= max) {
        return value;
      }
    } catch (NumberFormatException e) {
      // reported below
    }
    throw new BadRequestException("INVALID_PARAMETER", name + " must be a whole number from " + min + " to " + max);
  }

  /** A malformed id can't match a row: 404, like any unknown id. */
  private static long idParam(Context ctx, EntityDefinition<?> entity) {
    try {
      long id = Long.parseLong(ctx.pathParam("id"));
      if (id > 0) {
        return id;
      }
    } catch (NumberFormatException ignored) {
      // fall through
    }
    throw notFound(entity);
  }

  /** The "version" the client last read, from the PATCH body or DELETE's ?version=. */
  private static Long version(Object raw) {
    if (raw == null) {
      throw ValidationFailedException.field("version", "REQUIRED", "the version you last read is required");
    }
    try {
      return FieldType.LONG.coerce(raw);
    } catch (IllegalArgumentException e) {
      throw ValidationFailedException.field("version", "INVALID_VALUE", "must be an integer");
    }
  }

  private static ResourceNotFoundException notFound(EntityDefinition<?> entity) {
    return new ResourceNotFoundException("NOT_FOUND", entity.name() + " not found");
  }
}

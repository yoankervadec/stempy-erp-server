package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.net.URI;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Operator;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.query.FilterNode;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryExecutor;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.query.QuerySpec;
import com.lesconstructionssapete.stempyerpserver.platform.query.QuerySpecParser;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.query.SQLQuery;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
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
 * QUERY  /masterdata/products        search (QuerySpecParser body)    masterdata.product.read    200 page
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

  public record PageInfo(int limit, int offset) {
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

  /** @param clock should tick in whole seconds, like a MySQL TIMESTAMP */
  public EntityEndpoints(UnitOfWorkFactory uowFactory, QueryLimits limits, PermissionChecker permissions,
      EventBus eventBus, Clock clock) {
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(limits, "limits");
    this.parser = new QuerySpecParser(limits);
    this.translator = new QueryTranslator(limits);
  }

  /**
   * Registers the routes for {@code actions} (all of them if none given).
   * Fails at startup if a needed permission isn't declared, or DELETE is asked
   * for an entity without soft delete.
   */
  public <E> void register(Routes routes, String path, EntityDefinition<E> entity, String permissionPrefix,
      Action... actions) {
    Set<Action> allowed = actions.length == 0 ? EnumSet.allOf(Action.class) : EnumSet.of(actions[0], actions);
    String one = path + "/{id}";
    if (allowed.contains(Action.READ)) {
      routes.query(path, query(entity, Action.READ.permission(permissionPrefix)));
      routes.get(one, get(entity, Action.READ.permission(permissionPrefix)));
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
  }

  // ---- handlers (public for routes that need a custom path) ----

  /** QUERY: search, filter, sort, page. An empty body means "first page, default order". */
  public <E> Handler query(EntityDefinition<E> entity, String permission) {
    permissions.requireDeclared(permission);
    return ctx -> {
      RequestContext rc = authorize(ctx, permission);
      QuerySpec spec = parser.parse(ctx.body().isBlank() ? Map.of() : JsonBodies.read(ctx, BODY_TYPE.getType()));
      SQLQuery<E> query = translator.translate(entity, spec, rc.companyId(), access(rc));
      List<Map<String, Object>> rows = uowFactory.executeReadOnly(uow -> queries.listAsMaps(uow, query));
      ctx.json(new Page(rows, new PageInfo(spec.limit(), spec.offset())));
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
      ctx.json(uowFactory.execute(uow -> {
        var before = writer.update(uow, update, id, rc.companyId());
        eventBus.publish(uow, new EntityChanged.Updated(entity.name(), id, rc.companyId(), rc.userId(),
            rc.requestId(), wc.now(), changed, FieldChanges.updated(before, update.values())));
        return row(uow, entity, id, rc).orElseThrow(() -> notFound(entity));
      }));
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
      uowFactory.run(uow -> {
        writer.update(uow, delete, id, rc.companyId());
        eventBus.publish(uow, new EntityChanged.Deleted(entity.name(), id, rc.companyId(), rc.userId(),
            rc.requestId(), wc.now()));
      });
      ctx.status(204);
    };
  }

  // ---- internals ----

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

  /** The row as this caller sees it (same company, live, readable fields only). */
  private <E> Optional<Map<String, Object>> row(UnitOfWork uow, EntityDefinition<E> entity, long id,
      RequestContext rc) {
    QuerySpec byId = new QuerySpec(new FilterNode.Condition(entity.id().name(), Operator.EQUALS, id),
        List.of(), 1, 0);
    SQLQuery<E> query = translator.translate(entity, byId, rc.companyId(), access(rc));
    return queries.listAsMaps(uow, query).stream().findFirst();
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

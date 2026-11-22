package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryExecutor;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.query.QuerySpec;
import com.lesconstructionssapete.stempyerpserver.platform.query.QuerySpecParser;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.query.SQLQuery;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Generic, read-only "search this entity" endpoint, so a module gets a working
 * list/filter/sort/page route from its EntityDefinition alone:
 *
 * <pre>
 * routes.query("/masterdata/products", queries.handler(Product.DEF, "masterdata.product.read"));
 * </pre>
 *
 * Request: the QuerySpecParser body (where / sort / page). An empty body means
 * "first page, default order".
 *
 * Response:
 *
 * <pre>
 * <code class="json">
 * { "data": [ { "id": 1, "name": "..." } ], "page": { "limit": 50, "offset": 0 } }
 * </code>
 * </pre>
 *
 * The caller must hold the route's permission (403 PERMISSION_DENIED). Every
 * request is scoped to the caller's company, hides soft-deleted rows, and
 * only shows/filters fields the caller may read (see QueryTranslator). The
 * body is validated and translated BEFORE a database connection is taken, so
 * bad queries never cost a pooled connection.
 *
 * This is a platform service built once by ModuleHost and published in the
 * ServiceRegistry; modules resolve it in registerRoutes().
 */
public final class EntityQueryEndpoint {

  /** The response body. */
  public record Page(List<Map<String, Object>> data, PageInfo page) {
  }

  public record PageInfo(int limit, int offset) {
  }

  private static final TypeReference<Map<String, Object>> BODY_TYPE = new TypeReference<>() {
  };

  private final UnitOfWorkFactory uowFactory;
  private final QuerySpecParser parser;
  private final QueryTranslator translator;
  private final QueryExecutor executor = new QueryExecutor();
  private final PermissionChecker permissions;

  public EntityQueryEndpoint(UnitOfWorkFactory uowFactory, QueryLimits limits, PermissionChecker permissions) {
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    Objects.requireNonNull(limits, "limits");
    this.parser = new QuerySpecParser(limits);
    this.translator = new QueryTranslator(limits);
  }

  /**
   * A Javalin handler that searches {@code entity} for callers holding
   * {@code permission}. Fails at startup if no module declared the permission.
   */
  public <E> Handler handler(EntityDefinition<E> entity, String permission) {
    Objects.requireNonNull(entity, "entity");
    permissions.requireDeclared(permission);
    return ctx -> handle(ctx, entity, permission);
  }

  private <E> void handle(Context ctx, EntityDefinition<E> entity, String permission) {
    RequestContext rc = RequestContexts.require(ctx);
    permissions.require(rc, permission);

    QuerySpec spec = parser.parse(readBody(ctx));
    FieldAccess access = FieldAccess.forPermissions(rc::hasPermission);
    SQLQuery<E> query = translator.translate(entity, spec, rc.companyId(), access);

    List<Map<String, Object>> rows = uowFactory.executeReadOnly(uow -> executor.listAsMaps(uow, query));
    ctx.json(new Page(rows, new PageInfo(spec.limit(), spec.offset())));
  }

  private static Map<String, Object> readBody(Context ctx) {
    if (ctx.body().isBlank()) {
      return Map.of();
    }
    return JsonBodies.read(ctx, BODY_TYPE.getType());
  }
}

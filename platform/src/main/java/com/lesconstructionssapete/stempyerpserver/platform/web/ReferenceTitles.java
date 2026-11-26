package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.web.ResourceCatalog.Resource;

/**
 * Names the records a row points at (docs/ARCHITECTURE.md §23.10), so a list
 * shows "HW Hardware" rather than category 3, with no call per row:
 *
 * <pre>
 * "_refs": { "productCategoryId": { "id": 3, "title": "HW", "subtitle": "Hardware" } }
 * </pre>
 *
 * For every reference field of the row (ResourceCatalog.referenceOf) whose
 * target the caller can read: one SELECT ... WHERE id IN (...) per target
 * entity and page, in the caller's company. Deleted targets are named too (a
 * row may still point at them) and marked "deleted": true. The title and
 * subtitle follow the target's hints and the caller's field permissions: an
 * unreadable title is the id, an unreadable subtitle is left out.
 *
 * Rows without any reference field the caller can read get no _refs key.
 */
final class ReferenceTitles {

  /** At most this many ids per IN list. */
  private static final int CHUNK = 500;

  private final ResourceCatalog catalog;
  private final PermissionChecker permissions;

  ReferenceTitles(ResourceCatalog catalog, PermissionChecker permissions) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
  }

  /** Adds "_refs" to each row (mutable maps from QueryExecutor.listAsMaps), in this unit of work. */
  void attach(UnitOfWork uow, EntityDefinition<?> entity, List<Map<String, Object>> rows, RequestContext rc) {
    if (rows.isEmpty()) {
      return;
    }
    FieldAccess access = FieldAccess.forPermissions(rc::hasPermission);
    Map<String, Resource> targets = new LinkedHashMap<>();
    for (Field<?, ?> f : entity.readableFields(access)) {
      catalog.referenceOf(entity, f)
          .filter(r -> r.permission(Action.READ).map(p -> permissions.has(rc, p)).orElse(false))
          .ifPresent(r -> targets.put(f.name(), r));
    }
    if (targets.isEmpty()) {
      return;
    }
    // ids wanted per target entity, then their titles, once per entity
    Map<Resource, Set<Long>> wanted = new LinkedHashMap<>();
    targets.forEach((field, target) -> {
      for (Map<String, Object> row : rows) {
        if (row.get(field) instanceof Number n) {
          wanted.computeIfAbsent(target, t -> new LinkedHashSet<>()).add(n.longValue());
        }
      }
    });
    Map<Resource, Map<Long, Map<String, Object>>> titles = new HashMap<>();
    wanted.forEach((target, ids) -> titles.put(target, load(uow, target.entity(), ids, rc, access)));
    for (Map<String, Object> row : rows) {
      Map<String, Object> refs = new LinkedHashMap<>();
      targets.forEach((field, target) -> {
        if (row.get(field) instanceof Number n) {
          Map<String, Object> ref = titles.getOrDefault(target, Map.of()).get(n.longValue());
          if (ref != null) {
            refs.put(field, ref);
          }
        }
      });
      row.put("_refs", refs);
    }
  }

  private static Map<Long, Map<String, Object>> load(UnitOfWork uow, EntityDefinition<?> target, Set<Long> ids,
      RequestContext rc, FieldAccess access) {
    Field<?, ?> id = target.id();
    Field<?, ?> title = readable(target.titleField(), access) ? target.titleField() : id;
    Optional<? extends Field<?, ?>> subtitle = target.subtitleField().filter(f -> readable(f, access));
    String deleted = target.softDeleteColumn();

    StringBuilder select = new StringBuilder("SELECT ").append(id.column()).append(", ").append(title.column());
    subtitle.ifPresent(f -> select.append(", ").append(f.column()));
    if (deleted != null) {
      select.append(", ").append(deleted).append(" IS NOT NULL AS ref_deleted");
    }
    select.append(" FROM ").append(target.table()).append(" WHERE ");
    if (target.companyColumn() != null) {
      select.append(target.companyColumn()).append(" = ? AND ");
    }
    select.append(id.column()).append(" IN (");

    Map<Long, Map<String, Object>> out = new HashMap<>();
    List<Long> all = new ArrayList<>(ids);
    for (int from = 0; from < all.size(); from += CHUNK) {
      List<Long> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
      List<Object> params = new ArrayList<>();
      if (target.companyColumn() != null) {
        params.add(rc.companyId());
      }
      params.addAll(chunk);
      String sql = select + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
      for (Map<String, Object> ref : Jdbc.queryList(uow, sql,
          rs -> ref(rs, id, title, subtitle.orElse(null), deleted != null), params.toArray())) {
        out.put((Long) ref.get("id"), ref);
      }
    }
    return out;
  }

  private static Map<String, Object> ref(ResultSet rs, Field<?, ?> id, Field<?, ?> title, Field<?, ?> subtitle,
      boolean softDelete) throws SQLException {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("id", id.type().read(rs, id.column()));
    ref.put("title", title.type().read(rs, title.column()));
    if (subtitle != null) {
      ref.put("subtitle", subtitle.type().read(rs, subtitle.column()));
    }
    if (softDelete && rs.getBoolean("ref_deleted")) {
      ref.put("deleted", true);
    }
    return ref;
  }

  private static boolean readable(Field<?, ?> f, FieldAccess access) {
    return !f.isInternal() && access.canRead(f);
  }
}

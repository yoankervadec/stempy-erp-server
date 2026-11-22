package com.lesconstructionssapete.stempyerpserver.platform.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.query.Bind;

/**
 * Builds INSERT, partial UPDATE and soft-delete statements from what a client
 * sent ({ "fieldName": value, ... }). Pure: no database, so a bad body never
 * costs a pooled connection.
 *
 * Only declared, non-internal fields the user may read AND write, and that
 * the operation allows (isCreatable / isUpdatable), are accepted. Values are
 * coerced and validated per field; every problem is reported together (422).
 * Client text never reaches the SQL string: identifiers come from the
 * definition, values are bound.
 *
 * The server's columns are added here: company_id, the audit columns, the
 * version bump. Rows are addressed by id + company (+ not soft-deleted) and
 * guarded by the version the client last read.
 */
public final class WriteBuilder {

  public <E> SQLWrite<E> insert(EntityDefinition<E> def, Map<String, ?> body, WriteContext ctx,
      FieldAccess access) {
    List<FieldError> errors = new ArrayList<>();
    Map<Field<E, ?>, Object> values = accept(def, body, access, true, errors);
    for (Field<E, ?> f : def.fields()) {
      if (f.isRequired() && f.isCreatable() && !body.containsKey(f.name())) {
        Validation.into(errors).required(f.name(), null);
      }
    }
    throwIfAny(errors);

    List<String> columns = new ArrayList<>();
    List<Bind> binds = new ArrayList<>();
    values.forEach((f, v) -> {
      columns.add(f.column());
      binds.add(new Bind(f.type(), v));
    });
    if (def.companyColumn() != null) {
      columns.add(def.companyColumn());
      binds.add(new Bind(FieldType.LONG, ctx.companyId()));
    }
    addAudit(def.createdAtColumn(), def.createdByColumn(), ctx, columns, binds);
    addAudit(def.updatedAtColumn(), def.updatedByColumn(), ctx, columns, binds);

    String sql = "INSERT INTO " + def.table() + " (" + String.join(", ", columns) + ") VALUES ("
        + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
    return new SQLWrite<>(def, sql, binds, values, null, List.of());
  }

  /** {@code expectedVersion}: the version the client last read (null only for unversioned entities). */
  public <E> SQLWrite<E> update(EntityDefinition<E> def, long id, Long expectedVersion, Map<String, ?> patch,
      WriteContext ctx, FieldAccess access) {
    if (patch.isEmpty()) {
      throw ValidationFailedException.field("", "EMPTY_PATCH", "at least one field must be provided");
    }
    requireVersion(def, expectedVersion);
    List<FieldError> errors = new ArrayList<>();
    Map<Field<E, ?>, Object> values = accept(def, patch, access, false, errors);
    throwIfAny(errors);

    List<String> set = new ArrayList<>();
    List<Bind> binds = new ArrayList<>();
    values.forEach((f, v) -> {
      set.add(f.column() + " = ?");
      binds.add(new Bind(f.type(), v));
    });
    return guarded(def, id, expectedVersion, ctx, set, binds, values);
  }

  /** Sets the soft-delete column; refuses entities without one. */
  public <E> SQLWrite<E> softDelete(EntityDefinition<E> def, long id, Long expectedVersion, WriteContext ctx) {
    if (def.softDeleteColumn() == null) {
      throw new IllegalStateException(def.name() + " has no soft-delete column");
    }
    requireVersion(def, expectedVersion);
    return guarded(def, id, expectedVersion, ctx,
        new ArrayList<>(List.of(def.softDeleteColumn() + " = ?")),
        new ArrayList<>(List.of(new Bind(FieldType.INSTANT, ctx.now()))), Map.of());
  }

  // ---- internals ----

  /** The accepted, coerced and validated values, in body order. */
  private static <E> Map<Field<E, ?>, Object> accept(EntityDefinition<E> def, Map<String, ?> body,
      FieldAccess access, boolean creating, List<FieldError> errors) {
    Map<Field<E, ?>, Object> values = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : body.entrySet()) {
      Field<E, ?> f = def.field(entry.getKey()).orElse(null);
      if (f == null || f.isInternal() || !access.canRead(f)) {
        errors.add(new FieldError(entry.getKey(), "UNKNOWN_FIELD", "unknown field '" + entry.getKey() + "'"));
      } else if (!(creating ? f.isCreatable() : f.isUpdatable())) {
        errors.add(new FieldError(f.name(), "READ_ONLY", creating ? "is set by the server" : "cannot be changed"));
      } else if (!access.canWrite(f)) {
        errors.add(new FieldError(f.name(), "FORBIDDEN", "you may not change this field"));
      } else {
        coerce(f, entry.getValue(), values, errors);
      }
    }
    return values;
  }

  private static <E, V> void coerce(Field<E, V> f, Object raw, Map<Field<E, ?>, Object> values,
      List<FieldError> errors) {
    V value;
    try {
      value = f.type().coerce(raw);
    } catch (IllegalArgumentException e) {
      errors.add(new FieldError(f.name(), "INVALID_VALUE", e.getMessage()));
      return;
    }
    int before = errors.size();
    f.validate(value, errors);
    if (errors.size() == before) {
      values.put(f, value);
    }
  }

  private static <E> SQLWrite<E> guarded(EntityDefinition<E> def, long id, Long expectedVersion, WriteContext ctx,
      List<String> set, List<Bind> binds, Map<Field<E, ?>, Object> values) {
    if (def.updatedAtColumn() != null) {
      set.add(def.updatedAtColumn() + " = ?");
      binds.add(new Bind(FieldType.INSTANT, ctx.now()));
      set.add(def.updatedByColumn() + " = ?");
      binds.add(new Bind(FieldType.LONG, ctx.userId()));
    }
    if (def.versionField() != null) {
      String v = def.versionField().column();
      set.add(v + " = " + v + " + 1");
    }

    StringBuilder where = new StringBuilder(def.id().column()).append(" = ?");
    List<Bind> whereBinds = new ArrayList<>(List.of(new Bind(FieldType.LONG, id)));
    if (def.companyColumn() != null) {
      where.append(" AND ").append(def.companyColumn()).append(" = ?");
      whereBinds.add(new Bind(FieldType.LONG, ctx.companyId()));
    }
    if (def.softDeleteColumn() != null) {
      where.append(" AND ").append(def.softDeleteColumn()).append(" IS NULL");
    }
    String existsSql = "SELECT 1 FROM " + def.table() + " WHERE " + where;
    List<Bind> existsBinds = List.copyOf(whereBinds);
    if (def.versionField() != null) {
      where.append(" AND ").append(def.versionField().column()).append(" = ?");
      whereBinds.add(new Bind(FieldType.LONG, expectedVersion));
    }

    binds.addAll(whereBinds);
    String sql = "UPDATE " + def.table() + " SET " + String.join(", ", set) + " WHERE " + where;
    return new SQLWrite<>(def, sql, binds, values, existsSql, existsBinds);
  }

  private static void addAudit(String atColumn, String byColumn, WriteContext ctx, List<String> columns,
      List<Bind> binds) {
    if (atColumn != null) {
      columns.add(atColumn);
      binds.add(new Bind(FieldType.INSTANT, ctx.now()));
      columns.add(byColumn);
      binds.add(new Bind(FieldType.LONG, ctx.userId()));
    }
  }

  private static void requireVersion(EntityDefinition<?> def, Long expectedVersion) {
    if (def.versionField() != null && expectedVersion == null) {
      throw ValidationFailedException.field("version", "REQUIRED", "the version you last read is required");
    }
  }

  private static void throwIfAny(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", errors);
    }
  }
}

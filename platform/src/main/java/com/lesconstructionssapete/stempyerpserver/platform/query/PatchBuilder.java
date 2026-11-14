package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Builds a partial UPDATE from { "fieldName": value, ... } sent by the client.
 *
 * Only declared, updatable, non-internal fields the user may write are
 * accepted. Values are coerced and validated per field; every problem is
 * collected and reported together.
 * The row is addressed by id + company (+ not soft-deleted) and guarded by the
 * version column when the entity has one.
 */
public final class PatchBuilder {

  public <E> SQLUpdate<E> build(EntityDefinition<E> def, long id, long companyId, Long expectedVersion,
      Map<String, ?> patch, FieldAccess access) {
    List<FieldError> errors = new ArrayList<>();
    if (patch.isEmpty()) {
      throw new ValidationFailedException("Validation failed", List.of(
          new FieldError("", "EMPTY_PATCH", "at least one field must be provided")));
    }
    if (def.versionField() != null && expectedVersion == null) {
      throw new ValidationFailedException("Validation failed", List.of(
          new FieldError("version", "REQUIRED", "the current version is required")));
    }

    StringBuilder set = new StringBuilder();
    List<Bind> binds = new ArrayList<>();

    // Iterate over each field in the patch and validate it before building the SET
    // clause.
    for (Map.Entry<String, ?> entry : patch.entrySet()) {
      Field<E, ?> f = def.field(entry.getKey()).orElse(null);
      if (f == null || f.isInternal() || !access.canRead(f)) {
        errors.add(new FieldError(entry.getKey(), "UNKNOWN_FIELD", "unknown field '" + entry.getKey() + "'"));
      } else if (!f.isUpdatable()) {
        errors.add(new FieldError(f.name(), "READ_ONLY", "cannot be changed"));
      } else if (!access.canWrite(f)) {
        errors.add(new FieldError(f.name(), "FORBIDDEN", "you may not change this field"));
      } else {
        assign(f, entry.getValue(), set, binds, errors);
      }
    }
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", errors);
    }

    if (def.updatedAtColumn() != null) {
      set.append(", ").append(def.updatedAtColumn()).append(" = NOW()");
    }
    if (def.versionField() != null) {
      String v = def.versionField().column();
      set.append(", ").append(v).append(" = ").append(v).append(" + 1");
    }

    StringBuilder where = new StringBuilder(def.id().column()).append(" = ?");
    List<Bind> whereBinds = new ArrayList<>();
    whereBinds.add(new Bind(FieldType.LONG, id));
    if (def.companyColumn() != null) {
      where.append(" AND ").append(def.companyColumn()).append(" = ?");
      whereBinds.add(new Bind(FieldType.LONG, companyId));
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

    String sql = "UPDATE " + def.table() + " SET " + set + " WHERE " + where;
    List<Bind> all = new ArrayList<>(binds);
    all.addAll(whereBinds);
    return new SQLUpdate<>(def, sql, all, existsSql, existsBinds);
  }

  private <E, V> void assign(Field<E, V> f, Object raw, StringBuilder set, List<Bind> binds,
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
    if (errors.size() > before) {
      return;
    }
    if (!set.isEmpty()) {
      set.append(", ");
    }
    set.append(f.column()).append(" = ?");
    binds.add(new Bind(f.type(), value));
  }
}

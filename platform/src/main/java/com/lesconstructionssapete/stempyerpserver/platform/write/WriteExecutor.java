package com.lesconstructionssapete.stempyerpserver.platform.write;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.query.Bind;

/**
 * Runs SQLWrites in the caller's transaction, after the checks that need the
 * database:
 *
 * - unique fields: no other row of the company has the value (422
 * ALREADY_EXISTS);
 * - references: a value written to a many-to-one relation's foreign key points
 * at a live row of the SAME company (422 INVALID_REFERENCE), so a client can't
 * link to another company's data.
 *
 * The database constraints stay the final guard (a concurrent insert of the
 * same code); ErrorTranslator turns their violations into 409 / 422.
 */
public final class WriteExecutor {

  /** Returns the generated id. */
  public <E> long insert(UnitOfWork uow, SQLWrite<E> insert, long companyId) {
    check(uow, insert, companyId, null);
    try (PreparedStatement ps = uow.connection().prepareStatement(insert.sql(), Statement.RETURN_GENERATED_KEYS)) {
      bindAll(ps, insert.binds());
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new IllegalStateException("No generated id for " + insert.entity().name());
        }
        return keys.getLong(1);
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /**
   * 404 if the row doesn't exist (for this company), 409 if its version moved
   * on.
   *
   * @return the values the changed fields had before, read under a row lock
   *         (empty for a soft delete, which changes no client field)
   */
  public <E> Map<Field<E, ?>, Object> update(UnitOfWork uow, SQLWrite<E> update, long id, long companyId) {
    check(uow, update, companyId, id);
    try {
      Map<Field<E, ?>, Object> before = before(uow, update);
      int changed;
      try (PreparedStatement ps = uow.connection().prepareStatement(update.sql())) {
        bindAll(ps, update.binds());
        changed = ps.executeUpdate();
      }
      if (changed == 1) {
        return before;
      }
      String entity = update.entity().name();
      if (exists(uow, update.existsSql(), update.existsBinds())) {
        throw new ConflictException("VERSION_CONFLICT", entity + " was changed by someone else; reload and retry");
      }
      throw new ResourceNotFoundException("NOT_FOUND", entity + " not found");
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /** Locks the row and reads the current values of the fields being changed; empty if no such row. */
  private static <E> Map<Field<E, ?>, Object> before(UnitOfWork uow, SQLWrite<E> update) throws SQLException {
    if (update.beforeSql() == null) {
      return Map.of();
    }
    try (PreparedStatement ps = uow.connection().prepareStatement(update.beforeSql())) {
      bindAll(ps, update.beforeBinds());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Map.of(); // the UPDATE finds nothing either: 404
        }
        Map<Field<E, ?>, Object> values = new LinkedHashMap<>();
        for (Field<E, ?> f : update.values().keySet()) {
          values.put(f, f.type().read(rs, f.column()));
        }
        return values;
      }
    }
  }

  // ---- checks ----

  private <E> void check(UnitOfWork uow, SQLWrite<E> write, long companyId, Long excludeId) {
    EntityDefinition<E> def = write.entity();
    List<FieldError> errors = new ArrayList<>();
    try {
      for (Map.Entry<Field<E, ?>, Object> e : write.values().entrySet()) {
        Field<E, ?> f = e.getKey();
        Object value = e.getValue();
        if (value == null) {
          continue;
        }
        if (f.isUnique() && taken(uow, def, f, value, companyId, excludeId)) {
          errors.add(new FieldError(f.name(), "ALREADY_EXISTS", "is already used"));
        }
        for (Relation<E, ?> r : def.relations()) {
          if (r.kind() == Relation.Kind.MANY_TO_ONE && r.foreignKey().equals(f.column())
              && !referenceExists(uow, r.target(), value, companyId)) {
            errors.add(new FieldError(f.name(), "INVALID_REFERENCE", "does not exist"));
          }
        }
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", errors);
    }
  }

  /** Soft-deleted rows count: the database's unique index includes them. */
  private static <E> boolean taken(UnitOfWork uow, EntityDefinition<E> def, Field<E, ?> f, Object value,
      long companyId, Long excludeId) throws SQLException {
    StringBuilder sql = new StringBuilder("SELECT 1 FROM ").append(def.table())
        .append(" WHERE ").append(f.column()).append(" = ?");
    List<Bind> binds = new ArrayList<>(List.of(new Bind(f.type(), value)));
    if (def.companyColumn() != null) {
      sql.append(" AND ").append(def.companyColumn()).append(" = ?");
      binds.add(new Bind(FieldType.LONG, companyId));
    }
    if (excludeId != null) {
      sql.append(" AND ").append(def.id().column()).append(" <> ?");
      binds.add(new Bind(FieldType.LONG, excludeId));
    }
    return exists(uow, sql + " LIMIT 1", binds);
  }

  private static boolean referenceExists(UnitOfWork uow, EntityDefinition<?> target, Object id, long companyId)
      throws SQLException {
    StringBuilder sql = new StringBuilder("SELECT 1 FROM ").append(target.table())
        .append(" WHERE ").append(target.id().column()).append(" = ?");
    List<Bind> binds = new ArrayList<>(List.of(new Bind(FieldType.LONG, id)));
    if (target.companyColumn() != null) {
      sql.append(" AND ").append(target.companyColumn()).append(" = ?");
      binds.add(new Bind(FieldType.LONG, companyId));
    }
    if (target.softDeleteColumn() != null) {
      sql.append(" AND ").append(target.softDeleteColumn()).append(" IS NULL");
    }
    return exists(uow, sql.toString(), binds);
  }

  private static boolean exists(UnitOfWork uow, String sql, List<Bind> binds) throws SQLException {
    try (PreparedStatement ps = uow.connection().prepareStatement(sql)) {
      bindAll(ps, binds);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void bindAll(PreparedStatement ps, List<Bind> binds) throws SQLException {
    int index = 1;
    for (Bind bind : binds) {
      bind.apply(ps, index++);
    }
  }
}

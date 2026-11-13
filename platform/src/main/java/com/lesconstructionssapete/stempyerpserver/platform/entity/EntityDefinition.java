package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The single place an entity is described: table, fields, relations, mappers.
 * Declared once as a static constant on the entity class.
 */
public class EntityDefinition<E> {

  private static final Pattern API_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
  private static final Pattern SQL_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

  private final String name;
  private final String table;
  private final Supplier<E> factory;
  private final Map<String, Field<E, ?>> fields = new LinkedHashMap<>();
  private final Map<String, Relation<E, ?>> relations = new LinkedHashMap<>();

  private String companyColumn = "company_id";
  private String softDeleteColumn;
  private String updatedAtColumn;
  private Field<E, Long> idField;
  private Field<E, Long> versionField;

  private EntityDefinition(String name, String table, Supplier<E> factory) {
    this.name = apiName(name);
    this.table = sqlName(table);
    this.factory = factory;
  }

  public static <E> EntityDefinition<E> of(String name, String table, Supplier<E> factory) {
    return new EntityDefinition<>(name, table, factory);
  }

  // ---- entity-level settings ----

  /**
   * Rows are scoped to a company through this column. Pass the column name, or
   * use globalTable().
   */
  public EntityDefinition<E> companyColumn(String column) {
    this.companyColumn = sqlName(column);
    return this;
  }

  /** A table shared by all companies (currencies, countries...). */
  public EntityDefinition<E> globalTable() {
    this.companyColumn = null;
    return this;
  }

  /** A row is live while this nullable timestamp column is NULL. */
  public EntityDefinition<E> softDelete(String column) {
    this.softDeleteColumn = sqlName(column);
    return this;
  }

  /** Column set to now() on every patch. */
  public EntityDefinition<E> updatedAt(String column) {
    this.updatedAtColumn = sqlName(column);
    return this;
  }

  // ---- field declarations ----

  public <V> Field<E, V> field(String name, String column, FieldType<V> type,
      Function<E, V> getter, BiConsumer<E, V> setter) {
    apiName(name);
    sqlName(column);
    if (fields.containsKey(name)) {
      throw new IllegalStateException("Duplicate field '" + name + "' on " + this.name);
    }
    Field<E, V> f = new Field<>(name, column, type, getter, setter);
    fields.put(name, f);
    return f;
  }

  public Field<E, Long> id(String column, Function<E, Long> getter, BiConsumer<E, Long> setter) {
    if (idField != null) {
      throw new IllegalStateException("Second id field on " + name);
    }
    idField = field("id", column, FieldType.LONG, getter, setter).markId();
    return idField;
  }

  /** Optimistic-locking counter, bumped by every patch. */
  public Field<E, Long> version(String column, Function<E, Long> getter, BiConsumer<E, Long> setter) {
    if (versionField != null) {
      throw new IllegalStateException("Second version field on " + name);
    }
    versionField = field("version", column, FieldType.LONG, getter, setter).markVersion();
    return versionField;
  }

  /** The tenant column, mapped but never exposed. */
  public Field<E, Long> companyId(Function<E, Long> getter, BiConsumer<E, Long> setter) {
    if (companyColumn == null) {
      throw new IllegalStateException("globalTable() entity " + name + " has no company column");
    }
    return field("companyId", companyColumn, FieldType.LONG, getter, setter).internal();
  }

  public Field<E, String> string(String name, String column, Function<E, String> g, BiConsumer<E, String> s) {
    return field(name, column, FieldType.STRING, g, s);
  }

  /** 64-bit integer. */
  public Field<E, Long> integer(String name, String column, Function<E, Long> g, BiConsumer<E, Long> s) {
    return field(name, column, FieldType.LONG, g, s);
  }

  public Field<E, BigDecimal> decimal(String name, String column, Function<E, BigDecimal> g,
      BiConsumer<E, BigDecimal> s) {
    return field(name, column, FieldType.DECIMAL, g, s);
  }

  public Field<E, Boolean> bool(String name, String column, Function<E, Boolean> g, BiConsumer<E, Boolean> s) {
    return field(name, column, FieldType.BOOLEAN, g, s);
  }

  public Field<E, LocalDate> date(String name, String column, Function<E, LocalDate> g, BiConsumer<E, LocalDate> s) {
    return field(name, column, FieldType.DATE, g, s);
  }

  public Field<E, Instant> instant(String name, String column, Function<E, Instant> g, BiConsumer<E, Instant> s) {
    return field(name, column, FieldType.INSTANT, g, s);
  }

  public <V extends Enum<V>> Field<E, V> enumeration(String name, String column, Class<V> type,
      Function<E, V> g, BiConsumer<E, V> s) {
    return field(name, column, FieldType.enumOf(type), g, s);
  }

  // ---- relation declarations ----

  public <T> Relation<E, T> manyToOne(String name, Supplier<EntityDefinition<T>> target, String foreignKeyOnThisTable) {
    return relation(name, Relation.Kind.MANY_TO_ONE, target, foreignKeyOnThisTable);
  }

  public <T> Relation<E, T> oneToMany(String name, Supplier<EntityDefinition<T>> target,
      String foreignKeyOnTargetTable) {
    return relation(name, Relation.Kind.ONE_TO_MANY, target, foreignKeyOnTargetTable);
  }

  private <T> Relation<E, T> relation(String name, Relation.Kind kind, Supplier<EntityDefinition<T>> target,
      String fk) {
    apiName(name);
    sqlName(fk);
    if (relations.containsKey(name) || fields.containsKey(name)) {
      throw new IllegalStateException("Duplicate name '" + name + "' on " + this.name);
    }
    Relation<E, T> r = new Relation<>(name, kind, target, fk);
    relations.put(name, r);
    return r;
  }

  // ---- lookups ----

  public String name() {
    return name;
  }

  public String table() {
    return table;
  }

  /** Null for global tables. */
  public String companyColumn() {
    return companyColumn;
  }

  /** Null when the entity has no soft delete. */
  public String softDeleteColumn() {
    return softDeleteColumn;
  }

  public String updatedAtColumn() {
    return updatedAtColumn;
  }

  public Field<E, Long> id() {
    return idField;
  }

  /** Null when the entity has no optimistic locking. */
  public Field<E, Long> versionField() {
    return versionField;
  }

  public Optional<Field<E, ?>> field(String name) {
    return Optional.ofNullable(fields.get(name));
  }

  public Optional<Relation<E, ?>> relation(String name) {
    return Optional.ofNullable(relations.get(name));
  }

  public Collection<Field<E, ?>> fields() {
    return Collections.unmodifiableCollection(fields.values());
  }

  public Collection<Relation<E, ?>> relations() {
    return Collections.unmodifiableCollection(relations.values());
  }

  /** Fields the API may show to this user (internal fields never appear). */
  public List<Field<E, ?>> readableFields(FieldAccess access) {
    List<Field<E, ?>> out = new ArrayList<>();
    for (Field<E, ?> f : fields.values()) {
      if (!f.isInternal() && access.canRead(f)) {
        out.add(f);
      }
    }
    return out;
  }

  /**
   * Columns to SELECT: what the user may read, plus internal fields the server
   * needs.
   */
  public List<Field<E, ?>> selectFields(FieldAccess access) {
    List<Field<E, ?>> out = new ArrayList<>();
    for (Field<E, ?> f : fields.values()) {
      if (f.isInternal() || access.canRead(f)) {
        out.add(f);
      }
    }
    return out;
  }

  // ---- mappers ----

  /**
   * Builds an entity from the current row, reading the given fields by column
   * name.
   */
  public E mapRow(ResultSet rs, Collection<Field<E, ?>> columns) throws SQLException {
    E entity = factory.get();
    for (Field<E, ?> f : columns) {
      f.readInto(entity, rs);
    }
    return entity;
  }

  /**
   * API representation: field name -> value, only for the given (already
   * permission-filtered) fields.
   */
  public Map<String, Object> toMap(E entity, Collection<Field<E, ?>> visible) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Field<E, ?> f : visible) {
      out.put(f.name(), f.get(entity));
    }
    return out;
  }

  /** Called by EntityRegistry at startup so a broken definition fails fast. */
  void validateDefinition() {
    if (idField == null) {
      throw new IllegalStateException("Entity " + name + " has no id field");
    }
  }

  private static String apiName(String s) {
    if (!API_NAME.matcher(s).matches()) {
      throw new IllegalArgumentException("Invalid API name: " + s);
    }
    return s;
  }

  private static String sqlName(String s) {
    if (!SQL_NAME.matcher(s).matches()) {
      throw new IllegalArgumentException("Invalid SQL identifier: " + s);
    }
    return s;
  }

}

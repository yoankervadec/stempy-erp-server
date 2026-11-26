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
import java.util.Objects;
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
  private String createdAtColumn;
  private String createdByColumn;
  private String updatedAtColumn;
  private String updatedByColumn;
  private Field<E, Long> idField;
  private Field<E, Long> versionField;

  private Field<E, ?> titleField;
  private Field<E, ?> subtitleField;
  private List<Field<E, ?>> listColumns = List.of();
  private List<SortKey> defaultSort = List.of();
  private Presentation presentation;

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

  /**
   * Columns the server fills on writes: created_* on insert, updated_* on
   * insert and every update / soft delete, from the request's user and clock.
   */
  public EntityDefinition<E> audit(String createdAt, String createdBy, String updatedAt, String updatedBy) {
    this.createdAtColumn = sqlName(createdAt);
    this.createdByColumn = sqlName(createdBy);
    this.updatedAtColumn = sqlName(updatedAt);
    this.updatedByColumn = sqlName(updatedBy);
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

  // ---- UI hints (docs/ARCHITECTURE.md §23.4) ----

  /**
   * What a row is called in pickers, reference cells and titles. Default: the
   * first unique string field, else the id.
   */
  public EntityDefinition<E> titleField(Field<E, ?> field) {
    this.titleField = own(field, "titleField");
    return this;
  }

  /** Shown under or beside the title (a name beside a code). Default: none. */
  public EntityDefinition<E> subtitleField(Field<E, ?> field) {
    this.subtitleField = own(field, "subtitleField");
    return this;
  }

  /**
   * The columns a list shows at first, in order. Default: the readable fields
   * that are filterable or sortable, except the id and version.
   */
  @SafeVarargs
  public final EntityDefinition<E> listColumns(Field<E, ?>... fields) {
    List<Field<E, ?>> out = new ArrayList<>();
    for (Field<E, ?> f : fields) {
      out.add(own(f, "listColumns"));
    }
    this.listColumns = List.copyOf(out);
    return this;
  }

  /** A list's first order; every key must be a sortable field. Default: id descending. */
  public EntityDefinition<E> defaultSort(SortKey... keys) {
    List<SortKey> out = new ArrayList<>();
    for (SortKey k : keys) {
      if (fields.get(k.field().name()) != k.field()) {
        throw new IllegalArgumentException(name + ".defaultSort: " + k.field().name() + " is not a field of " + name);
      }
      if (!k.field().isSortable()) {
        throw new IllegalArgumentException(name + ".defaultSort: " + k.field().name() + " is not sortable");
      }
      out.add(k);
    }
    this.defaultSort = List.copyOf(out);
    return this;
  }

  /** Where one record opens. Default: PAGE when its layout has child tables, else MODAL. */
  public EntityDefinition<E> presentation(Presentation presentation) {
    this.presentation = Objects.requireNonNull(presentation, "presentation");
    return this;
  }

  private Field<E, ?> own(Field<E, ?> field, String hint) {
    if (field == null || fields.get(field.name()) != field) {
      throw new IllegalArgumentException(name + "." + hint + ": not a field of " + name);
    }
    if (field.isInternal()) {
      throw new IllegalArgumentException(name + "." + hint + ": " + field.name() + " is internal");
    }
    return field;
  }

  public Field<E, ?> titleField() {
    if (titleField != null) {
      return titleField;
    }
    for (Field<E, ?> f : fields.values()) {
      if (f.isUnique() && !f.isInternal() && f.type() == FieldType.STRING) {
        return f;
      }
    }
    return idField;
  }

  public Optional<Field<E, ?>> subtitleField() {
    return Optional.ofNullable(subtitleField);
  }

  /** As declared; empty when the default applies. */
  public List<Field<E, ?>> listColumns() {
    return listColumns;
  }

  /** As declared; empty when undeclared (QUERY then keeps id order). */
  public List<SortKey> declaredDefaultSort() {
    return defaultSort;
  }

  /** As declared, else id descending (what GET /meta suggests). */
  public List<SortKey> defaultSort() {
    return defaultSort.isEmpty() && idField != null ? List.of(SortKey.desc(idField)) : defaultSort;
  }

  /** As declared; MODAL when undeclared (GET /meta also looks at the layout: declaredPresentation()). */
  public Presentation presentation() {
    return presentation == null ? Presentation.MODAL : presentation;
  }

  /** Empty when the entity leaves it to its layout. */
  public Optional<Presentation> declaredPresentation() {
    return Optional.ofNullable(presentation);
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

  /** The audit columns are null when the entity has no audit(...). */
  public String createdAtColumn() {
    return createdAtColumn;
  }

  public String createdByColumn() {
    return createdByColumn;
  }

  public String updatedAtColumn() {
    return updatedAtColumn;
  }

  public String updatedByColumn() {
    return updatedByColumn;
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
    for (Field<E, ?> f : fields.values()) {
      Field<E, Long> currency = f.currencyField();
      if (currency != null && fields.get(currency.name()) != currency) {
        throw new IllegalStateException(name + "." + f.name() + ": currency field " + currency.name()
            + " is not a field of " + name);
      }
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

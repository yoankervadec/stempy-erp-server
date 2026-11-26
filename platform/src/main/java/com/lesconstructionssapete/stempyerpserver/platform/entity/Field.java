package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;

/**
 * One persisted attribute of an entity: API name, column, type,
 * accessors,declarative validation, query capabilities and permissions. Created
 * through EntityDef; configured fluently while the entity class initializes,
 * then treated as immutable.
 */
public final class Field<E, V> {

  private final String name;
  private final String column;
  private final FieldType<V> type;
  private final Function<E, V> getter;
  private final BiConsumer<E, V> setter;

  private boolean required;
  private boolean filterable;
  private boolean sortable;
  private boolean creatable = true;
  private boolean updatable = true;
  private boolean unique;
  private boolean internal;
  private boolean id;
  private boolean version;
  private Integer maxLength;
  private BigDecimal min;
  private BigDecimal max;
  private Pattern pattern;
  private String readPermission;
  private String writePermission;

  Field(
      String name,
      String column,
      FieldType<V> type,
      Function<E, V> getter,
      BiConsumer<E, V> setter) {
    this.name = name;
    this.column = column;
    this.type = type;
    this.getter = getter;
    this.setter = setter;
  }

  // ---- fluent configuration ----

  public Field<E, V> required() {
    this.required = true;
    return this;
  }

  public Field<E, V> filterable() {
    this.filterable = true;
    return this;
  }

  public Field<E, V> sortable() {
    this.sortable = true;
    return this;
  }

  /**
   * Never written by clients, on create or update (still readable). For values
   * the server sets: audit columns, computed totals.
   */
  public Field<E, V> readOnly() {
    this.creatable = false;
    this.updatable = false;
    return this;
  }

  /** Set when the row is created, never changed afterwards. */
  public Field<E, V> immutable() {
    this.updatable = false;
    return this;
  }

  /**
   * No two rows of the company (of the table, for a global table) share this
   * value, soft-deleted rows included, matching the database's unique index.
   * Checked before writing, so the client gets a field error instead of a
   * constraint violation.
   */
  public Field<E, V> unique() {
    this.unique = true;
    return this;
  }

  /**
   * Mapped from the database but never exposed: not readable, filterable,
   * sortable or patchable.
   */
  public Field<E, V> internal() {
    this.internal = true;
    this.creatable = false;
    this.updatable = false;
    return this;
  }

  public Field<E, V> maxLength(int maxLength) {
    this.maxLength = maxLength;
    return this;
  }

  public Field<E, V> min(String min) {
    this.min = new BigDecimal(min);
    return this;
  }

  public Field<E, V> max(String max) {
    this.max = new BigDecimal(max);
    return this;
  }

  /** Whole-value regular expression for string fields. */
  public Field<E, V> pattern(String regex) {
    this.pattern = Pattern.compile(regex);
    return this;
  }

  public Field<E, V> readPermission(String permission) {
    this.readPermission = permission;
    return this;
  }

  public Field<E, V> writePermission(String permission) {
    this.writePermission = permission;
    return this;
  }

  Field<E, V> markId() {
    this.id = true;
    this.creatable = false;
    this.updatable = false;
    this.filterable = true;
    this.sortable = true;
    return this;
  }

  Field<E, V> markVersion() {
    this.version = true;
    this.creatable = false;
    this.updatable = false;
    return this;
  }

  // ---- accessors ----

  public String name() {
    return name;
  }

  public String column() {
    return column;
  }

  public FieldType<V> type() {
    return type;
  }

  public boolean isRequired() {
    return required;
  }

  public boolean isFilterable() {
    return filterable;
  }

  public boolean isSortable() {
    return sortable;
  }

  public boolean isCreatable() {
    return creatable;
  }

  public boolean isUpdatable() {
    return updatable;
  }

  public boolean isUnique() {
    return unique;
  }

  public boolean isInternal() {
    return internal;
  }

  public boolean isId() {
    return id;
  }

  public boolean isVersion() {
    return version;
  }

  /** Null when unbounded. */
  public Integer maxLength() {
    return maxLength;
  }

  /** Null when unbounded. */
  public BigDecimal min() {
    return min;
  }

  /** Null when unbounded. */
  public BigDecimal max() {
    return max;
  }

  public String readPermission() {
    return readPermission;
  }

  public String writePermission() {
    return writePermission;
  }

  // ---- behaviour ----

  public V get(E entity) {
    return getter.apply(entity);
  }

  void readInto(E entity, ResultSet rs) throws SQLException {
    setter.accept(entity, type.read(rs, column));
  }

  /**
   * Declarative checks only (required, length, pattern, range). Business rules
   * live in operations.
   */
  public void validate(V value, List<FieldError> errors) {
    Validation v = Validation.into(errors);
    if (required) {
      v.required(name, value);
    }
    if (value instanceof String s) {
      if (maxLength != null) {
        v.maxLength(name, s, maxLength);
      }
      if (pattern != null) {
        v.matches(name, s, pattern);
      }
    }
    if (value instanceof Number n && (min != null || max != null)) {
      BigDecimal d = value instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
      if (min != null) {
        v.min(name, d, min);
      }
      if (max != null) {
        v.max(name, d, max);
      }
    }
  }


}

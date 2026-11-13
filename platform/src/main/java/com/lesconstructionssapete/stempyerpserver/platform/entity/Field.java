package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;

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
  private boolean updatable = true;
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

  /** Cannot be changed through the patch endpoint (still readable). */
  public Field<E, V> readOnly() {
    this.updatable = false;
    return this;
  }

  /**
   * Mapped from the database but never exposed: not readable, filterable,
   * sortable or patchable.
   */
  public Field<E, V> internal() {
    this.internal = true;
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
    this.updatable = false;
    this.filterable = true;
    this.sortable = true;
    return this;
  }

  Field<E, V> markVersion() {
    this.version = true;
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

  public boolean isUpdatable() {
    return updatable;
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
    if (value == null) {
      if (required) {
        errors.add(new FieldError(name, "required", "is required"));
      }
      return;
    }
    if (value instanceof String s) {
      if (maxLength != null && s.length() > maxLength) {
        errors.add(new FieldError(name, "too_long", "must be at most " + maxLength + " characters"));
      }
      if (pattern != null && !pattern.matcher(s).matches()) {
        errors.add(new FieldError(name, "invalid_format", "has an invalid format"));
      }
    }
    if (value instanceof Number n && (min != null || max != null)) {
      BigDecimal d = value instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
      if (min != null && d.compareTo(min) < 0) {
        errors.add(new FieldError(name, "too_small", "must be at least " + min.toPlainString()));
      }
      if (max != null && d.compareTo(max) > 0) {
        errors.add(new FieldError(name, "too_large", "must be at most " + max.toPlainString()));
      }
    }
  }

}

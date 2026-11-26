package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
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
  private Integer scale;
  private Format format;
  private Field<E, Long> currencyField;
  private Supplier<? extends EntityDefinition<?>> references;
  private V defaultValue;

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

  // ---- UI hints (docs/ARCHITECTURE.md §23.4) ----

  /**
   * Decimals a value may have, for a decimal field: also a rule, so a value
   * with more is refused (TOO_MANY_DECIMALS) instead of being rounded by the
   * database. Match the column's scale. Undeclared: clients show 2 and
   * nothing is checked.
   */
  public Field<E, V> scale(int scale) {
    requireType(FieldType.DECIMAL, "scale");
    if (scale < 0 || scale > 10) {
      throw new IllegalArgumentException(name + ": scale must be 0 to 10");
    }
    this.scale = scale;
    return this;
  }

  /** An amount in the company's currency. */
  public Field<E, V> money() {
    requireType(FieldType.DECIMAL, "money");
    this.format = Format.MONEY;
    return this;
  }

  /** An amount in the currency this row's {@code currencyField} names. */
  public Field<E, V> money(Field<E, Long> currencyField) {
    money();
    this.currencyField = Objects.requireNonNull(currencyField, "currencyField");
    return this;
  }

  /** A percentage, stored as such (5.25 is 5.25 %). */
  public Field<E, V> percent() {
    requireType(FieldType.DECIMAL, "percent");
    this.format = Format.PERCENT;
    return this;
  }

  /** Text edited in a text area. */
  public Field<E, V> multiline() {
    requireType(FieldType.STRING, "multiline");
    this.format = Format.MULTILINE;
    return this;
  }

  /**
   * This integer holds the id of a row of {@code target}, though no relation
   * is declared (no join needed). A many-to-one relation whose foreign key is
   * this field's column says the same without this.
   */
  public Field<E, V> references(Supplier<? extends EntityDefinition<?>> target) {
    requireType(FieldType.LONG, "references");
    this.references = Objects.requireNonNull(target, "target");
    return this;
  }

  /**
   * The value a new record starts with (GET {path}/new), e.g. isEnabled true.
   * Only a starting value for forms: a create that leaves the field out still
   * gets the database's default.
   */
  public Field<E, V> defaultValue(V value) {
    this.defaultValue = Objects.requireNonNull(value, "value");
    return this;
  }

  /** Null when none is declared. */
  public V defaultValue() {
    return defaultValue;
  }

  private void requireType(FieldType<?> expected, String hint) {
    if (type != expected) {
      throw new IllegalStateException(name + ": " + hint + "() needs a " + expected.name() + " field, not "
          + type.name());
    }
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

  /** The declared scale, null when undeclared. */
  public Integer scale() {
    return scale;
  }

  /** Null when the type says it all. */
  public Format format() {
    return format;
  }

  /** For MONEY in a per-row currency; null otherwise. */
  public Field<E, Long> currencyField() {
    return currencyField;
  }

  /** The entity this field's value points at, when declared with references(). */
  public Optional<EntityDefinition<?>> referencedEntity() {
    return references == null ? Optional.empty() : Optional.of(references.get());
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
    if (scale != null && value instanceof BigDecimal d) {
      v.maxScale(name, d, scale);
    }
  }


}

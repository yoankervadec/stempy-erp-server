package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;

import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.CONTAINS;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.EQUALS;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.GREATER_THAN;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.GREATER_THAN_OR_EQUAL;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.IN;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.IS_NULL;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.LESS_THAN;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.LESS_THAN_OR_EQUAL;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.NOT_EQUALS;
import static com.lesconstructionssapete.stempyerpserver.platform.entity.Operator.STARTS_WITH;

/**
 * One supported column type: how to read it from a ResultSet, bind it to a
 * PreparedStatement, coerce a raw JSON value into it, and which filter
 * operators make sense for it.
 */
public final class FieldType<T> {

  @FunctionalInterface
  public interface Reader<T> {
    T read(ResultSet resultSet, String column) throws SQLException;
  }

  @FunctionalInterface
  public interface Binder<T> {
    void bind(PreparedStatement statement, int index, T value) throws SQLException;
  }

  private static final Set<Operator> EQUALITY = EnumSet.of(EQUALS, NOT_EQUALS, IN, IS_NULL);
  private static final Set<Operator> ORDERED = EnumSet.of(EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL,
      LESS_THAN, LESS_THAN_OR_EQUAL, IN, IS_NULL);
  private static final Set<Operator> TEXT = EnumSet.of(EQUALS, NOT_EQUALS, IN, IS_NULL, CONTAINS, STARTS_WITH);

  public static final FieldType<String> STRING = new FieldType<>(
      "string", String.class,
      ResultSet::getString,
      PreparedStatement::setString,
      FieldType::toStringValue,
      TEXT);

  public static final FieldType<Long> LONG = new FieldType<>(
      "integer", Long.class,
      (rs, c) -> {
        long v = rs.getLong(c);
        return rs.wasNull() ? null : v;
      },
      PreparedStatement::setLong,
      FieldType::toLong,
      ORDERED);

  public static final FieldType<BigDecimal> DECIMAL = new FieldType<>(
      "decimal", BigDecimal.class,
      ResultSet::getBigDecimal,
      PreparedStatement::setBigDecimal,
      FieldType::toDecimal,
      ORDERED);

  public static final FieldType<Boolean> BOOLEAN = new FieldType<>(
      "boolean", Boolean.class,
      (rs, c) -> {
        boolean v = rs.getBoolean(c);
        return rs.wasNull() ? null : v;
      },
      PreparedStatement::setBoolean,
      FieldType::toBoolean,
      EnumSet.of(EQUALS, NOT_EQUALS, IS_NULL));

  public static final FieldType<LocalDate> DATE = new FieldType<>(
      "date", LocalDate.class,
      (rs, c) -> rs.getObject(c, LocalDate.class),
      PreparedStatement::setObject,
      FieldType::toDate,
      ORDERED);

  public static final FieldType<Instant> INSTANT = new FieldType<>(
      "timestamp", Instant.class,
      (rs, c) -> {
        OffsetDateTime odt = rs.getObject(c, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
      },
      (ps, i, v) -> ps.setObject(i, v == null ? null : OffsetDateTime.ofInstant(v, ZoneOffset.UTC)),
      FieldType::toInstant,
      ORDERED);

  /**
   * Enum stored as its name in a varchar column (avoid native Postgres enums;
   * they need casts).
   */
  public static <E extends Enum<E>> FieldType<E> enumOf(Class<E> type) {
    String expected = "one of " + Arrays.toString(type.getEnumConstants());
    return new FieldType<>(
        "enum", type,
        (rs, c) -> {
          String s = rs.getString(c);
          return s == null ? null : Enum.valueOf(type, s);
        },
        (ps, i, v) -> ps.setString(i, v == null ? null : v.name()),
        raw -> {
          if (raw instanceof String s) {
            try {
              return Enum.valueOf(type, s);
            } catch (IllegalArgumentException e) {
              throw bad(expected);
            }
          }
          throw bad(expected);
        },
        EQUALITY);
  }

  private final String name;
  private final Class<T> javaType;
  private final Reader<T> reader;
  private final Binder<T> binder;
  private final Function<Object, T> coercer;
  private final Set<Operator> operators;

  private FieldType(
      String name,
      Class<T> javaType,
      Reader<T> reader,
      Binder<T> binder,
      Function<Object, T> coercer,
      Set<Operator> operators) {
    this.name = name;
    this.javaType = javaType;
    this.reader = reader;
    this.binder = binder;
    this.coercer = coercer;
    this.operators = Set.copyOf(operators);
  }

  public String name() {
    return name;
  }

  public Class<T> javaType() {
    return javaType;
  }

  public Set<Operator> operators() {
    return operators;
  }

  public T read(ResultSet rs, String column) throws SQLException {
    return reader.read(rs, column);
  }

  /**
   * Coerces a raw JSON value (String, Number, Boolean) into T. Throws
   * IllegalArgumentException.
   */
  public T coerce(Object raw) {
    return raw == null ? null : coercer.apply(raw);
  }

  /**
   * Binds a value that is already of type T (the cast makes a wrong type fail
   * loudly, not silently).
   */
  public void bindObject(PreparedStatement ps, int index, Object value) throws SQLException {
    binder.bind(ps, index, javaType.cast(value));
  }

  @Override
  public String toString() {
    return name;
  }

  // ---- coercion helpers ----

  private static IllegalArgumentException bad(String expected) {
    return new IllegalArgumentException("expected " + expected);
  }

  private static String toStringValue(Object raw) {
    if (raw instanceof String s) {
      return s;
    }
    throw bad("a string");
  }

  private static Long toLong(Object raw) {
    try {
      if (raw instanceof Number n) {
        return new BigDecimal(n.toString()).longValueExact();
      }
      if (raw instanceof String s) {
        return Long.valueOf(s.trim());
      }
    } catch (ArithmeticException | NumberFormatException e) {
      throw bad("an integer");
    }
    throw bad("an integer");
  }

  private static BigDecimal toDecimal(Object raw) {
    try {
      if (raw instanceof Number n) {
        return new BigDecimal(n.toString());
      }
      if (raw instanceof String s) {
        return new BigDecimal(s.trim());
      }
    } catch (NumberFormatException e) {
      throw bad("a decimal number");
    }
    throw bad("a decimal number");
  }

  private static Boolean toBoolean(Object raw) {
    if (raw instanceof Boolean b) {
      return b;
    }
    throw bad("true or false");
  }

  private static LocalDate toDate(Object raw) {
    try {
      if (raw instanceof String s) {
        return LocalDate.parse(s);
      }
    } catch (DateTimeException e) {
      throw bad("a date (yyyy-MM-dd)");
    }
    throw bad("a date (yyyy-MM-dd)");
  }

  private static Instant toInstant(Object raw) {
    try {
      if (raw instanceof String s) {
        return OffsetDateTime.parse(s).toInstant();
      }
    } catch (DateTimeException e) {
      throw bad("an ISO-8601 timestamp with offset");
    }
    throw bad("an ISO-8601 timestamp with offset");
  }

}

package com.lesconstructionssapete.stempyerpserver.platform.write;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/** Builds the {@code changes} of an EntityChanged from a write's values. Pure. */
public final class FieldChanges {

  private FieldChanges() {
  }

  /** A creation: every value the client set, old value null. */
  public static <E> Map<String, FieldChange> created(Map<Field<E, ?>, Object> values) {
    Map<String, FieldChange> changes = new LinkedHashMap<>();
    values.forEach((f, v) -> changes.put(f.name(), new FieldChange(null, v)));
    return changes;
  }

  /**
   * An update: the fields whose value differs from before, by value
   * (19.9900 read from DECIMAL(19,4) equals 19.99 sent by the client).
   */
  public static <E> Map<String, FieldChange> updated(Map<Field<E, ?>, Object> before, Map<Field<E, ?>, Object> after) {
    Map<String, FieldChange> changes = new LinkedHashMap<>();
    after.forEach((f, newValue) -> {
      Object oldValue = before.get(f);
      if (!sameValue(oldValue, newValue)) {
        changes.put(f.name(), new FieldChange(oldValue, newValue));
      }
    });
    return changes;
  }

  static boolean sameValue(Object a, Object b) {
    if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
      return x.compareTo(y) == 0;
    }
    return Objects.equals(a, b);
  }
}

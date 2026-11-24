package com.lesconstructionssapete.stempyerpserver.platform.write;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

class FieldChangesTest {

  private static Map<Field<TestWidget, ?>, Object> values(Object name, Object cost) {
    Map<Field<TestWidget, ?>, Object> m = new LinkedHashMap<>();
    m.put(TestWidget.NAME, name);
    m.put(TestWidget.COST, cost);
    return m;
  }

  @Test
  void aCreationListsEverySetValueWithNoOldValue() {
    var changes = FieldChanges.created(values("Bolt", new BigDecimal("1.50")));
    assertEquals(List.of("name", "cost"), List.copyOf(changes.keySet())); // field order kept
    assertEquals(new FieldChange(null, "Bolt"), changes.get("name"));
  }

  @Test
  void anUpdateKeepsOnlyRealChanges() {
    var changes = FieldChanges.updated(values("Bolt", new BigDecimal("1.5000")), values("Big bolt",
        new BigDecimal("1.50")));
    // 1.5000 (as read from DECIMAL(19,4)) and 1.50 (as sent) are the same value: no change.
    assertEquals(Map.of("name", new FieldChange("Bolt", "Big bolt")), changes);
  }

  @Test
  void nullsAreValuesToo() {
    assertEquals(Map.of("cost", new FieldChange(null, new BigDecimal("2"))),
        FieldChanges.updated(values("Bolt", null), values("Bolt", new BigDecimal("2"))));
    assertEquals(Map.of("cost", new FieldChange(new BigDecimal("2"), null)),
        FieldChanges.updated(values("Bolt", new BigDecimal("2")), values("Bolt", null)));
    assertEquals(Map.of(), FieldChanges.updated(values(null, null), values(null, null)));
  }
}

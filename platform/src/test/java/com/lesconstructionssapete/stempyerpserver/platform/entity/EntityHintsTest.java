package com.lesconstructionssapete.stempyerpserver.platform.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestPart;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestStock;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;

/** The UI hints of docs/ARCHITECTURE.md §23.4: their defaults, and the mistakes refused at startup. */
class EntityHintsTest {

  static final class Row {
    Long id;
    String label;
    BigDecimal amount;
    Long currencyId;
  }

  private static EntityDefinition<Row> def(String name) {
    return EntityDefinition.of(name, name, Row::new);
  }

  @Test
  void theTitleDefaultsToTheFirstUniqueStringThenTheId() {
    var def = def("row");
    def.id("id", r -> r.id, (r, v) -> r.id = v);
    var label = def.string("label", "label", r -> r.label, (r, v) -> r.label = v);
    assertEquals("id", def.titleField().name());

    label.unique();

    assertEquals("label", def.titleField().name());
    assertEquals(List.of(SortKey.desc(def.id())), def.defaultSort());
    assertEquals(Presentation.MODAL, def.presentation());
  }

  @Test
  void hintsMustNameTheEntitysOwnFields() {
    assertThrows(IllegalArgumentException.class, () -> TestWidget.DEF.titleField(castTo(TestPart.CODE)));
    assertThrows(IllegalArgumentException.class, () -> TestWidget.DEF.listColumns(castTo(TestPart.NAME)));
    // the company column is internal
    var def = def("tenant");
    def.id("id", r -> r.id, (r, v) -> r.id = v);
    var company = def.companyId(r -> r.currencyId, (r, v) -> r.currencyId = v);
    assertThrows(IllegalArgumentException.class, () -> def.titleField(company));
  }

  @Test
  void theDefaultSortNeedsSortableFields() {
    // cost is filterable, not sortable
    assertThrows(IllegalArgumentException.class, () -> TestWidget.DEF.defaultSort(SortKey.asc(TestWidget.COST)));
  }

  @Test
  void formatsAndScalesNeedTheRightType() {
    var def = def("typed");
    var label = def.string("label", "label", r -> r.label, (r, v) -> r.label = v);
    var amount = def.decimal("amount", "amount", r -> r.amount, (r, v) -> r.amount = v);

    assertThrows(IllegalStateException.class, () -> label.scale(2));
    assertThrows(IllegalStateException.class, label::money);
    assertThrows(IllegalStateException.class, amount::multiline);
    assertThrows(IllegalStateException.class, () -> label.references(() -> TestPart.DEF));
    assertThrows(IllegalArgumentException.class, () -> amount.scale(11));
  }

  @Test
  void aDeclaredScaleIsARule() {
    List<FieldError> errors = new ArrayList<>();

    TestStock.QUANTITY.validate(new BigDecimal("1.2500"), errors); // scale 3; trailing zeros don't count
    assertTrue(errors.isEmpty());

    TestStock.QUANTITY.validate(new BigDecimal("1.2345"), errors);
    assertEquals("TOO_MANY_DECIMALS", errors.get(0).code());
  }

  @Test
  void theCurrencyFieldMustBelongToTheEntity() {
    var def = def("priced");
    def.id("id", r -> r.id, (r, v) -> r.id = v);
    var other = def("other");
    var foreign = other.integer("currencyId", "currency_id", r -> r.currencyId, (r, v) -> r.currencyId = v);
    def.decimal("amount", "amount", r -> r.amount, (r, v) -> r.amount = v).money(foreign);

    assertThrows(IllegalStateException.class, () -> new EntityRegistry().register(def).freeze());
  }

  @Test
  void aReferenceMustPointAtARegisteredEntity() {
    var registry = new EntityRegistry().register(TestStock.DEF);

    // TestStock references TestPart (relation and references()); TestPart isn't registered
    assertThrows(IllegalStateException.class, registry::freeze);
  }

  @SuppressWarnings("unchecked")
  private static Field<TestWidget, ?> castTo(Field<?, ?> f) {
    return (Field<TestWidget, ?>) f;
  }
}

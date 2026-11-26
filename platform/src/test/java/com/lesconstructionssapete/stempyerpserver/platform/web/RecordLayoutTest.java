package com.lesconstructionssapete.stempyerpserver.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestPart;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestStock;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;

/** A layout's mistakes fail when it is built (docs/ARCHITECTURE.md §23.7). */
class RecordLayoutTest {

  @Test
  void aCompleteLayoutBuilds() {
    RecordLayout layout = RecordLayout.of(TestPart.DEF)
        .section("general", 2, TestPart.CODE, TestPart.NAME)
        .collection(TestPart.STOCK_REL, c -> c.columns(TestStock.PART_ID, TestStock.QUANTITY)
            .footerSum(TestStock.QUANTITY).editable().fixedRows())
        .tab(RecordLayout.HISTORY);

    assertEquals("stock", layout.collections().get(0).name());
    assertTrue(layout.collections().get(0).editable());
    assertEquals(1, layout.collections().get(0).footerSums().size());
  }

  @Test
  void fieldsMustBeTheEntitysOwn() {
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestPart.DEF).section("general", 2, TestWidget.NAME));
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestPart.DEF).collection(TestPart.STOCK_REL, c -> c.columns(TestPart.CODE)));
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestPart.DEF).summary(TestStock.QUANTITY));
  }

  @Test
  void aCollectionGoesThroughTheEntitysOwnOneToMany() {
    // stock -> part is many-to-one
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestStock.DEF).collection(TestStock.PART_REL, c -> c.columns(TestPart.CODE)));
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestWidget.DEF).collection(TestPart.STOCK_REL, c -> c.columns(TestStock.PART_ID)));
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestPart.DEF).collection(TestPart.STOCK_REL, c -> { }));
  }

  @Test
  void footerSumsAreDecimalColumns() {
    assertThrows(IllegalArgumentException.class, () -> RecordLayout.of(TestPart.DEF)
        .collection(TestPart.STOCK_REL, c -> c.columns(TestStock.PART_ID).footerSum(TestStock.PART_ID)));
    assertThrows(IllegalArgumentException.class, () -> RecordLayout.of(TestPart.DEF)
        .collection(TestPart.STOCK_REL, c -> c.columns(TestStock.PART_ID).footerSum(TestStock.QUANTITY)));
  }

  @Test
  void namesColumnsAndTabsAreChecked() {
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestPart.DEF).section("general", 5, TestPart.CODE));
    assertThrows(IllegalArgumentException.class,
        () -> RecordLayout.of(TestPart.DEF).section("general", 1));
    assertThrows(IllegalArgumentException.class, () -> RecordLayout.of(TestPart.DEF)
        .section("general", 1, TestPart.CODE).section("general", 1, TestPart.NAME));
    assertThrows(IllegalArgumentException.class, () -> RecordLayout.of(TestPart.DEF).tab("attachments"));
  }
}

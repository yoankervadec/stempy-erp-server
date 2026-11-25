package com.lesconstructionssapete.stempyerpserver.platform.numbering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentTypesTest {

  private static final DocumentType CUSTOMER_ORDER = DocumentType.of("sales.customer-order", "Customer orders")
      .withParameters("STORE_NO", "TERMINAL_NO")
      .withDefaultSeries("CO", "CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}", Mode.GAPS_ALLOWED);

  private static String registerError(DocumentType type) {
    return assertThrows(IllegalArgumentException.class, () -> new DocumentTypes().register(type)).getMessage();
  }

  // ---- DocumentType ----

  @Test
  void declaration() {
    assertEquals("sales.customer-order", CUSTOMER_ORDER.key());
    assertEquals("Customer orders", CUSTOMER_ORDER.description());
    assertEquals(List.of("STORE_NO", "TERMINAL_NO"), CUSTOMER_ORDER.parameters());
    assertFalse(CUSTOMER_ORDER.isGaplessRequired());
    assertEquals(new DocumentType.DefaultSeries("CO", "CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}", Mode.GAPS_ALLOWED),
        CUSTOMER_ORDER.defaultSeries());
  }

  @Test
  void withersReturnCopies() {
    DocumentType plain = DocumentType.of("gl.journal-entry", "Journal entries");
    DocumentType gapless = plain.gaplessRequired();
    assertFalse(plain.isGaplessRequired());
    assertTrue(gapless.isGaplessRequired());
    assertNull(plain.defaultSeries());
    assertEquals(List.of("A", "B"), plain.withParameters("A").withParameters("B").parameters());
  }

  @ParameterizedTest
  @ValueSource(strings = { "invoice", "Sales.invoice", "sales.", ".invoice", "sales..invoice", "sales.invoice_x",
      "sales.1invoice", "sales invoice" })
  void badKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> DocumentType.of(key, "x"));
  }

  @Test
  void keyLengthAndDescription() {
    assertThrows(IllegalArgumentException.class, () -> DocumentType.of("a." + "b".repeat(99), "x"));
    assertThrows(IllegalArgumentException.class, () -> DocumentType.of("sales.invoice", " "));
    assertThrows(IllegalArgumentException.class, () -> DocumentType.of("sales.invoice", null));
    assertThrows(IllegalArgumentException.class, () -> DocumentType.of("sales.invoice", "x".repeat(256)));
  }

  @ParameterizedTest
  @ValueSource(strings = { "store_no", "1STORE", "STORE-NO", "SEQ", "" })
  void badParameterNames(String name) {
    assertThrows(IllegalArgumentException.class,
        () -> DocumentType.of("sales.invoice", "Invoices").withParameters(name));
  }

  @Test
  void parameterDeclaredTwice() {
    DocumentType type = DocumentType.of("sales.invoice", "Invoices").withParameters("STORE_NO");
    assertThrows(IllegalArgumentException.class, () -> type.withParameters("STORE_NO"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "co", "", "C O", "-CO", "C.O" })
  void badSeriesCodes(String code) {
    assertThrows(IllegalArgumentException.class,
        () -> DocumentType.of("sales.invoice", "Invoices").withDefaultSeries(code, "{SEQ:6}", Mode.GAPLESS));
  }

  @Test
  void goodSeriesCodes() {
    for (String code : List.of("CO", "WEB", "JE-2027", "A_1", "9", "X".repeat(50))) {
      DocumentType.of("sales.invoice", "Invoices").withDefaultSeries(code, "{SEQ:6}", Mode.GAPLESS);
    }
    assertThrows(IllegalArgumentException.class, () -> DocumentType.of("sales.invoice", "Invoices")
        .withDefaultSeries("X".repeat(51), "{SEQ:6}", Mode.GAPLESS));
  }

  // ---- DocumentTypes ----

  @Test
  void registerFindRequire() {
    DocumentTypes types = new DocumentTypes();
    types.register(CUSTOMER_ORDER);
    types.register(DocumentType.of("gl.journal-entry", "Journal entries"));
    types.freeze();
    assertEquals(CUSTOMER_ORDER, types.require("sales.customer-order"));
    assertTrue(types.find("sales.nope").isEmpty());
    assertEquals(List.of("sales.customer-order", "gl.journal-entry"),
        types.all().stream().map(DocumentType::key).toList());
    String message = assertThrows(IllegalStateException.class, () -> types.require("sales.nope")).getMessage();
    assertTrue(message.contains("registerDocumentTypes"), message);
  }

  @Test
  void registeredTwice() {
    DocumentTypes types = new DocumentTypes();
    types.register(CUSTOMER_ORDER);
    assertThrows(IllegalStateException.class, () -> types.register(CUSTOMER_ORDER));
  }

  @Test
  void frozen() {
    DocumentTypes types = new DocumentTypes();
    types.freeze();
    types.freeze();
    assertThrows(IllegalStateException.class, () -> types.register(CUSTOMER_ORDER));
  }

  @Test
  void defaultSeriesPatternIsCheckedAgainstTheParameters() {
    String message = registerError(DocumentType.of("sales.invoice", "Invoices")
        .withDefaultSeries("INV", "INV-{STORE_NO:3}{SEQ:6}", Mode.GAPLESS));
    assertTrue(message.startsWith("default series of sales.invoice: unknown parameter STORE_NO"), message);
    assertTrue(registerError(DocumentType.of("sales.invoice", "Invoices")
        .withDefaultSeries("INV", "INV-000", Mode.GAPLESS)).contains("no {SEQ:n} counter"));
  }

  @Test
  void gaplessRequiredRefusesADefaultWithGaps() {
    String message = registerError(DocumentType.of("gl.journal-entry", "Journal entries")
        .withDefaultSeries("JE", "JE{SEQ:8}", Mode.GAPS_ALLOWED)
        .gaplessRequired());
    assertTrue(message.contains("must be GAPLESS"), message);
    new DocumentTypes().register(DocumentType.of("gl.journal-entry", "Journal entries")
        .gaplessRequired()
        .withDefaultSeries("JE", "JE{SEQ:8}", Mode.GAPLESS));
  }
}

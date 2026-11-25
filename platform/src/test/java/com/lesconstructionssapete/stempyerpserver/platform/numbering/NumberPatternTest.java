package com.lesconstructionssapete.stempyerpserver.platform.numbering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

class NumberPatternTest {

  private static final List<String> POS = List.of("STORE_NO", "TERMINAL_NO");
  private static final String CUSTOMER_ORDER = "CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}";

  private static NumberPattern parse(String text) {
    return NumberPattern.parse(text, POS);
  }

  private static String parseError(String text) {
    return assertThrows(IllegalArgumentException.class, () -> parse(text)).getMessage();
  }

  private static NumberPattern.Applied store(String store, String terminal) {
    return parse(CUSTOMER_ORDER).apply(Map.of("STORE_NO", store, "TERMINAL_NO", terminal));
  }

  private static List<FieldError> applyErrors(String pattern, Map<String, String> values) {
    return assertThrows(ValidationFailedException.class, () -> parse(pattern).apply(values)).fieldErrors();
  }

  // ---- rendering ----

  @Test
  void counterOnly() {
    NumberPattern.Applied applied = parse("INV-{SEQ:6}").apply(Map.of());
    assertEquals("INV-000001", applied.format(1));
    assertEquals("INV-000042", applied.format(42));
    assertEquals("", applied.scope());
  }

  @Test
  void noPrefix() {
    assertEquals("00000042", parse("{SEQ:8}").apply(Map.of()).format(42));
  }

  @Test
  void literalsAroundAndBetweenTokens() {
    assertEquals("A-012/000007-Z", parse("A-{STORE_NO:3}/{SEQ:6}-Z").apply(Map.of("STORE_NO", "12")).format(7));
  }

  @Test
  void customerOrderFromTheDesign() {
    assertEquals("CO-01203012346", store("12", "3").format(12346));
  }

  @Test
  void counterGrowsPastItsWidth() {
    NumberPattern.Applied applied = parse("JE{SEQ:3}").apply(Map.of());
    assertEquals("JE999", applied.format(999));
    assertEquals("JE1000", applied.format(1000));
    assertEquals("JE000", applied.format(0));
  }

  @Test
  void negativeCounterIsRefused() {
    NumberPattern.Applied applied = parse("JE{SEQ:3}").apply(Map.of());
    assertThrows(IllegalArgumentException.class, () -> applied.format(-1));
  }

  @Test
  void counterTooLongForANumberIsAnError() {
    // 30 literal characters + 20 counter digits = 50: fits at its width...
    NumberPattern.Applied applied = parse("A".repeat(30) + "{SEQ:20}").apply(Map.of());
    assertEquals(50, applied.format(Long.MAX_VALUE).length());
    // ...but a 40-character prefix leaves room for 10 digits only.
    NumberPattern.Applied tight = parse("A".repeat(40) + "{SEQ:10}").apply(Map.of());
    assertEquals(50, tight.format(9_999_999_999L).length());
    assertThrows(IllegalStateException.class, () -> tight.format(10_000_000_000L));
  }

  @Test
  void parameterOrderInThePatternIsKept() {
    assertEquals("03-012-0001",
        parse("{TERMINAL_NO:2}-{STORE_NO:3}-{SEQ:4}").apply(Map.of("STORE_NO", "12", "TERMINAL_NO", "3"))
            .format(1));
  }

  // ---- parameter values ----

  @Test
  void valuesAreTrimmedAndPadded() {
    assertEquals("CO-01203000001", store(" 12 ", "3").format(1));
  }

  @Test
  void valueAtExactlyItsWidth() {
    assertEquals("CO-12345000001", store("123", "45").format(1));
  }

  @Test
  void lettersAndDashesAreAllowedAndKeptAsGiven() {
    assertEquals("CO-0aB-1000001", store("aB", "-1").format(1));
  }

  @Test
  void valuesTheSeriesDoesNotUseAreIgnored() {
    NumberPattern.Applied applied = parse("WEB-{SEQ:6}").apply(Map.of("STORE_NO", "12", "TERMINAL_NO", "!!"));
    assertEquals("WEB-000001", applied.format(1));
    assertEquals("", applied.scope());
  }

  @Test
  void everyBadValueIsReported() {
    Map<String, String> values = new HashMap<>();
    values.put("STORE_NO", "1234");
    values.put("TERMINAL_NO", null);
    List<FieldError> errors = applyErrors(CUSTOMER_ORDER, values);
    assertEquals(2, errors.size());
    assertTrue(errors.contains(new FieldError("params.STORE_NO", "TOO_LONG", "STORE_NO is longer than 3 characters")));
    assertTrue(errors.contains(new FieldError("params.TERMINAL_NO", "REQUIRED", "TERMINAL_NO is required")));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   " })
  void blankValueIsRequired(String value) {
    List<FieldError> errors = applyErrors("{STORE_NO:3}{SEQ:4}", Map.of("STORE_NO", value));
    assertEquals(List.of("REQUIRED"), errors.stream().map(FieldError::code).toList());
  }

  @ParameterizedTest
  @ValueSource(strings = { "1 2", "1;2", "a=b", "1_2", "1.2", "é" })
  void badCharactersAreRefused(String value) {
    List<FieldError> errors = applyErrors("{STORE_NO:5}{SEQ:4}", Map.of("STORE_NO", value));
    assertEquals(List.of("INVALID_CHARACTERS"), errors.stream().map(FieldError::code).toList());
    assertEquals("params.STORE_NO", errors.get(0).field());
  }

  // ---- scope: one counter per combination of values ----

  @Test
  void scopeUsesPaddedValuesSortedByName() {
    assertEquals("STORE_NO=012;TERMINAL_NO=03", store("12", "3").scope());
    assertEquals("STORE_NO=012;TERMINAL_NO=03",
        parse("{TERMINAL_NO:2}{STORE_NO:3}{SEQ:4}").apply(Map.of("STORE_NO", "12", "TERMINAL_NO", "3")).scope());
  }

  @Test
  void sameValueWrittenDifferentlyIsTheSameCounter() {
    assertEquals(store("12", "3").scope(), store("012", "03").scope());
  }

  @Test
  void differentTerminalsHaveDifferentCounters() {
    assertNotEquals(store("12", "3").scope(), store("12", "4").scope());
  }

  @Test
  void fixedWidthsKeepNumbersApart() {
    // Store 1 + terminal 23 and store 12 + terminal 3 would both print "123" unpadded.
    Set<String> numbers = new HashSet<>();
    Set<String> scopes = new HashSet<>();
    for (String[] pair : new String[][] { { "1", "23" }, { "12", "3" }, { "123", "" + 4 }, { "1", "2" } }) {
      NumberPattern.Applied applied = store(pair[0], pair[1]);
      assertTrue(scopes.add(applied.scope()));
      assertTrue(numbers.add(applied.format(5)), "duplicate number for " + pair[0] + "/" + pair[1]);
    }
  }

  // ---- parsing ----

  @Test
  void parametersOfThePattern() {
    assertEquals(Set.of("STORE_NO", "TERMINAL_NO"), parse(CUSTOMER_ORDER).parameters());
    assertEquals(List.of("STORE_NO", "TERMINAL_NO"), List.copyOf(parse(CUSTOMER_ORDER).parameters()));
    assertEquals(Set.of(), parse("INV-{SEQ:6}").parameters());
  }

  @Test
  void patternMayUseSomeDeclaredParameters() {
    assertEquals(Set.of("STORE_NO"), parse("S{STORE_NO:3}-{SEQ:6}").parameters());
  }

  @Test
  void textAndEquality() {
    assertEquals(CUSTOMER_ORDER, parse(CUSTOMER_ORDER).text());
    assertEquals(CUSTOMER_ORDER, parse(CUSTOMER_ORDER).toString());
    assertEquals(parse(CUSTOMER_ORDER), parse(CUSTOMER_ORDER));
    assertEquals(parse(CUSTOMER_ORDER).hashCode(), parse(CUSTOMER_ORDER).hashCode());
    assertNotEquals(parse("INV-{SEQ:6}"), parse("INV-{SEQ:7}"));
  }

  @Test
  void widthBounds() {
    assertEquals("X1", parse("X{SEQ:1}").apply(Map.of()).format(1));
    assertEquals("0".repeat(19) + "1", parse("{SEQ:20}").apply(Map.of()).format(1));
    assertEquals("007", parse("{SEQ:03}").apply(Map.of()).format(7));
  }

  @Test
  void longestNumberIsAllowed() {
    String pattern = "A".repeat(NumberPattern.MAX_NUMBER_LENGTH - 6) + "{SEQ:6}";
    assertEquals(NumberPattern.MAX_NUMBER_LENGTH, parse(pattern).apply(Map.of()).format(1).length());
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
      "INV-000001                            | no {SEQ:n} counter",
      "{SEQ:6}-{SEQ:6}                       | more than one {SEQ} counter",
      "INV-{SEQ}                             | SEQ needs a width",
      "{STORE_NO}{SEQ:6}                     | STORE_NO needs a width",
      "{SEQ:0}                               | width of SEQ must be a number from 1 to 20",
      "{SEQ:21}                              | width of SEQ must be a number from 1 to 20",
      "{SEQ:100}                             | width of SEQ must be a number from 1 to 20",
      "{SEQ:}                                | width of SEQ must be a number from 1 to 20",
      "{SEQ:-1}                              | width of SEQ must be a number from 1 to 20",
      "{SEQ:x}                               | width of SEQ must be a number from 1 to 20",
      "{SEQ:6:2}                             | width of SEQ must be a number from 1 to 20",
      "{:6}{SEQ:6}                           | token at position 1 has no name",
      "{}{SEQ:6}                             | token at position 1 has no name",
      "{seq:6}                               | unknown parameter seq at position 1",
      "{CUSTOMER_NO:3}{SEQ:6}                | unknown parameter CUSTOMER_NO at position 1: expected SEQ or one of STORE_NO, TERMINAL_NO",
      "{STORE_NO:3}{STORE_NO:3}{SEQ:6}       | parameter STORE_NO appears more than once",
      "INV-{SEQ:6                            | '{' at position 5 is not closed",
      "INV-{STORE_NO:3{SEQ:6}                | '{' at position 5 is not closed",
      "INV}-{SEQ:6}                          | '}' at position 4 has no matching '{'",
      "INV {SEQ:6}                           | character at position 4 is not allowed",
  })
  void badPatterns(String pattern, String expectedMessage) {
    String message = parseError(pattern);
    assertTrue(message.contains(expectedMessage), message);
  }

  @Test
  void emptyAndMissingPatterns() {
    assertEquals("pattern is empty", parseError(""));
    assertEquals("pattern is empty", parseError(null));
  }

  @Test
  void nonAsciiAndControlCharactersAreRefused() {
    assertTrue(parseError("FACTÉ-{SEQ:6}").contains("position 5 is not allowed"));
    assertTrue(parseError("INV\t{SEQ:6}").contains("position 4 is not allowed"));
  }

  @Test
  void patternTooLong() {
    assertTrue(parseError("{SEQ:6}" + "A".repeat(NumberPattern.MAX_LENGTH)).contains("longer than 255"));
  }

  @Test
  void numberTooLongAtItsWidths() {
    String message = parseError("A".repeat(NumberPattern.MAX_NUMBER_LENGTH - 5) + "{SEQ:6}");
    assertTrue(message.contains("numbers would be 51 characters long"), message);
    assertTrue(parseError("{STORE_NO:20}{TERMINAL_NO:20}{SEQ:11}").contains("51 characters"));
  }

  @Test
  void documentTypeWithoutParameters() {
    String message = assertThrows(IllegalArgumentException.class,
        () -> NumberPattern.parse("{STORE_NO:3}{SEQ:6}", List.of())).getMessage();
    assertTrue(message.endsWith("this document type has no parameters"), message);
  }

  @Test
  void longUnknownNamesAreCutInMessages() {
    String message = parseError("{" + "X".repeat(200) + ":3}{SEQ:6}");
    assertTrue(message.length() < 200, message);
  }
}

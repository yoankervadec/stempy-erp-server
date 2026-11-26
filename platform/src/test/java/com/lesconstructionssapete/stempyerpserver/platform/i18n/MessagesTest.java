package com.lesconstructionssapete.stempyerpserver.platform.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestStock;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

class MessagesTest {

  private static final Messages MESSAGES = Messages.load(MessagesTest.class.getClassLoader(),
      List.of("metatest", "absent"));

  @Test
  void aLanguageFallsBackToEnglishThenToTheHumanizedName() {
    assertEquals("Pièces", MESSAGES.label(Language.FR, "part", "part"));
    assertEquals("Parts", MESSAGES.label(Language.EN, "part", "part"));
    // no French "part.field.name", no shared one in this bundle
    assertEquals("Unit amount", MESSAGES.label(Language.FR, "unitAmount", "part.field.unitAmount"));
    // the first key with a text wins
    assertEquals("Pièce", MESSAGES.label(Language.FR, "part", "missing", "part.one", "part"));
  }

  @Test
  void errorsAreTranslatedWithTheirParamsButNeverIntoEnglish() {
    assertEquals(Optional.of("doit compter au plus 50 caractères"),
        MESSAGES.error(Language.FR, "TOO_LONG", Map.of("max", 50)));
    // English messages are the code's own
    assertEquals(Optional.empty(), MESSAGES.error(Language.EN, "TOO_LONG", Map.of("max", 50)));
    // a missing parameter: no translation rather than a broken one
    assertEquals(Optional.empty(), MESSAGES.error(Language.FR, "TOO_LONG", Map.of()));
    assertEquals(Optional.empty(), MESSAGES.error(Language.FR, "NO_SUCH_CODE", Map.of()));
  }

  // ---- parameters, written for the language ----

  private static final String NBSP = "\u00a0";

  private static String fmt(Language language, String template, Object... params) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < params.length; i += 2) {
      map.put((String) params[i], params[i + 1]);
    }
    return MESSAGES.format(language, template, map).orElseThrow();
  }

  @Test
  void decimalsKeepTheirOwnDecimalsAndUseTheLanguagesSeparators() {
    assertEquals("1" + NBSP + "234,50", fmt(Language.FR, "{q}", "q", new BigDecimal("1234.50")));
    assertEquals("1,234.50", fmt(Language.EN, "{q}", "q", new BigDecimal("1234.50")));
    assertEquals("3", fmt(Language.FR, "{q}", "q", new BigDecimal("3")));
    assertEquals("0,125", fmt(Language.FR, "{q}", "q", new BigDecimal("0.125")));
    // no exponent, ever
    assertEquals("1" + NBSP + "000", fmt(Language.FR, "{q}", "q", new BigDecimal("1E+3")));
  }

  @Test
  void moneyIsWrittenWithItsCurrency() {
    Money price = Money.of("1234.50", "CAD");
    assertEquals("Prix : 1" + NBSP + "234,50" + NBSP + "$", fmt(Language.FR, "Prix : {price}", "price", price));
    assertEquals("Price: $1,234.50", fmt(Language.EN, "Price: {price}", "price", price));
    assertEquals("US$12.50", fmt(Language.EN, "{p}", "p", Money.of("12.50", "USD")));
  }

  @Test
  void datesAreWrittenInWordsAndEnumsAsTheirLabels() {
    LocalDate date = LocalDate.of(2026, 11, 26);
    assertEquals("le 26 novembre 2026", fmt(Language.FR, "le {d}", "d", date));
    assertEquals("on November 26, 2026", fmt(Language.EN, "on {d}", "d", date));
    // enum.Status.AVAILABLE in the test texts
    assertEquals("Disponible", fmt(Language.FR, "{s}", "s", TestStock.Status.AVAILABLE));
    assertEquals("Reserved", fmt(Language.FR, "{s}", "s", TestStock.Status.RESERVED));
  }

  @Test
  void otherValuesAreWrittenAsTheyAre() {
    // product numbers, ids and counts: no grouping, no translation
    assertEquals("P-001 x 12345", fmt(Language.FR, "{no} x {n}", "no", "P-001", "n", 12345L));
  }

  @Test
  void textFillsAnyKeysPlaceholders() {
    var messages = Messages.load(MessagesTest.class.getClassLoader(), List.of("params"));
    Map<String, Object> params = Map.of("productNo", "P-001", "quantity", new BigDecimal("2.5"));

    assertEquals(Optional.of("Stock insuffisant pour P-001 : 2,5 disponibles"),
        messages.text(Language.FR, "stock.short", params));
    assertEquals(Optional.of("Not enough stock for P-001: 2.5 available"),
        messages.text(Language.EN, "stock.short", params));
    assertEquals(Optional.empty(), messages.text(Language.FR, "stock.short", Map.of("productNo", "P-001")));
    assertEquals(Optional.empty(), messages.text(Language.FR, "no.such.key", params));
  }

  // ---- loading ----

  @Test
  void aKeyDefinedByTwoBundlesFailsAtStartup() {
    assertThrows(IllegalStateException.class,
        () -> Messages.load(MessagesTest.class.getClassLoader(), List.of("dup1", "dup2")));
  }

  @Test
  void aKeyDefinedTwiceInOneFileFailsAtStartup() {
    assertThrows(RuntimeException.class,
        () -> Messages.load(MessagesTest.class.getClassLoader(), List.of("duplicatekey")));
  }

  @Test
  void anUnknownLanguageFailsAtStartup() {
    var e = assertThrows(IllegalStateException.class,
        () -> Messages.load(MessagesTest.class.getClassLoader(), List.of("badlanguage")));
    assertTrue(e.getMessage().contains("'de'"), e.getMessage());
  }

  @Test
  void humanizesApiNamesAndConstants() {
    assertEquals("Unit amount", Messages.humanize("unitAmount"));
    assertEquals("Is enabled", Messages.humanize("isEnabled"));
    assertEquals("Asset", Messages.humanize("ASSET"));
    assertEquals("Password changed", Messages.humanize("PASSWORD_CHANGED"));
    assertEquals("Id", Messages.humanize("id"));
  }

  @Test
  void anEmptySetHumanizesEverything() {
    assertEquals("Part", Messages.empty().label(Language.FR, "part", "part"));
    assertTrue(Messages.empty().all(Language.FR).isEmpty());
  }

  @Test
  void languagesParseFromCodesAndAcceptLanguage() {
    assertEquals(Optional.of(Language.FR), Language.fromCode("fr-CA"));
    assertEquals(Optional.of(Language.EN), Language.fromCode(" EN "));
    assertEquals(Optional.empty(), Language.fromCode("de"));
    assertEquals(Optional.empty(), Language.fromCode(null));
    assertEquals(Optional.of(Language.EN), Language.fromAcceptLanguage("en-US,en;q=0.9,fr;q=0.8"));
    assertEquals(Optional.of(Language.FR), Language.fromAcceptLanguage("de-DE, fr;q=0.5, en;q=0.4"));
    assertEquals(Optional.of(Language.EN), Language.fromAcceptLanguage("fr;q=0, en;q=0.1"));
    assertEquals(Optional.empty(), Language.fromAcceptLanguage("de, es"));
    assertEquals(Optional.empty(), Language.fromAcceptLanguage(""));
    assertEquals("fr", Language.FR.code());
  }
}

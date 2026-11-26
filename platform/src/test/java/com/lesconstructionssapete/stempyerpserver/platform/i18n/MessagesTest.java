package com.lesconstructionssapete.stempyerpserver.platform.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

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

  @Test
  void decimalsArePlainInTemplates() {
    assertEquals(Optional.of("1E+3 / 1000"),
        Messages.format("1E+3 / {v}", Map.of("v", new BigDecimal("1E+3"))));
  }

  @Test
  void aKeyDefinedByTwoBundlesFailsAtStartup() {
    assertThrows(IllegalStateException.class,
        () -> Messages.load(MessagesTest.class.getClassLoader(), List.of("dup1", "dup2")));
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

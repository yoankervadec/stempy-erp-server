package com.lesconstructionssapete.stempyerpserver.platform.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Period;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;

class AuditSettingsTest {

  @Test
  void defaultsToSevenYears() {
    assertEquals(Period.ofYears(7), AuditSettings.defaults().retention());
    assertEquals(Period.ofYears(7), AuditSettings.from(Settings.empty()).retention());
  }

  @ParameterizedTest
  @ValueSource(strings = { "P6Y", "P72M", "P10Y", " P8Y " })
  void acceptsSixYearsOrMore(String raw) {
    AuditSettings.from(Settings.of(Map.of("audit.retention", raw)));
  }

  @ParameterizedTest
  @ValueSource(strings = { "P5Y11M", "P1Y", "P90D", "PT1H", "seven years" })
  void refusesLessThanSixYearsOrGarbage(String raw) {
    assertThrows(IllegalArgumentException.class, () -> AuditSettings.from(Settings.of(Map.of("audit.retention", raw))));
  }
}

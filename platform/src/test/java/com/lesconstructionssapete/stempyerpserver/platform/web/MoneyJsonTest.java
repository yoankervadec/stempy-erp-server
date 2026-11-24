package com.lesconstructionssapete.stempyerpserver.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/** Money in HTTP bodies and outbox payloads: the amount as a string, the currency as its ISO code. */
class MoneyJsonTest {

  private final ObjectMapper json = JsonMapperFactory.newObjectMapper();

  record Line(String sku, Money unitPrice) {
  }

  @Test
  void writesAmountAsStringAndCurrencyAsCode() throws Exception {
    assertEquals("{\"amount\":\"1000.50\",\"currency\":\"CAD\"}", json.writeValueAsString(Money.of("1000.50", "CAD")));
  }

  @Test
  void readsItBack() throws Exception {
    Line line = new Line("B2-100", Money.of("19.9900", "CAD"));
    assertEquals(line, json.readValue(json.writeValueAsString(line), Line.class));
    assertEquals(Money.of("12.5", "USD"), json.readValue("{\"amount\": 12.5, \"currency\": \"USD\"}", Money.class));
  }
}

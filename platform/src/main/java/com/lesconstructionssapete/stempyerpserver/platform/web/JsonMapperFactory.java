package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * THE Jackson configuration for the whole application: HTTP bodies now, outbox
 * event payloads and audit records later. Use newObjectMapper() or
 * configure(...) so every JSON in the system behaves the same way.
 *
 * Choices made for an ERP, where silently wrong numbers are the worst kind of
 * bug:
 * - BigDecimal is written as a plain JSON string ("1000.50"), so a JavaScript
 * client never rounds an amount through a floating-point double. Numbers and
 * strings are both accepted on input.
 * - Dates and times are ISO-8601 strings, never numeric timestamps.
 * - Unknown properties on input are rejected, so a client typo ("qty" for
 * "quantity") fails loudly instead of being ignored.
 * - null for a primitive (long, int, boolean) is rejected, instead of silently
 * becoming 0/false.
 * - A fractional number for an integer field (1.5 for an int quantity) is
 * rejected, instead of truncated.
 */
public final class JsonMapperFactory {

  private JsonMapperFactory() {
  }

  public static ObjectMapper newObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    configure(mapper);
    return mapper;
  }

  /**
   * Applies the project settings to an existing mapper (Javalin hands us its own
   * to configure).
   */
  public static void configure(ObjectMapper mapper) {
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new SimpleModule().addSerializer(BigDecimal.class, new PlainBigDecimalSerializer()));

    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }

  /** toPlainString avoids scientific notation: 1E+3 is written as "1000". */
  private static final class PlainBigDecimalSerializer extends StdSerializer<BigDecimal> {
    PlainBigDecimalSerializer() {
      super(BigDecimal.class);
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider) throws IOException {
      gen.writeString(value.toPlainString());
    }
  }
}

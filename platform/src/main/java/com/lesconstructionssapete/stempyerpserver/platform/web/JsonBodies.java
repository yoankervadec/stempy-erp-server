package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.lang.reflect.Type;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * Reads a JSON request body with the project's Jackson settings, turning any
 * parse problem (malformed JSON, unknown property, wrong type) into a 400
 * instead of a 500.
 *
 * The client message is deliberately generic: Jackson's own messages can echo
 * the input, which may contain a password.
 */
public final class JsonBodies {

  private JsonBodies() {
  }

  public static <T> T read(Context ctx, Class<T> type) {
    return read(ctx, (Type) type);
  }

  public static <T> T read(Context ctx, Type type) {
    String body = ctx.body();
    if (body.isBlank()) {
      throw new BadRequestResponse("A JSON request body is required");
    }
    T parsed;
    try {
      parsed = ctx.jsonMapper().fromJsonString(body, type);
    } catch (Exception e) {
      // Exception, not RuntimeException: Javalin's (Kotlin) mapper rethrows
      // Jackson's checked JsonProcessingException undeclared.
      throw new BadRequestResponse("Request body is not valid JSON for this endpoint");
    }
    if (parsed == null) {
      throw new BadRequestResponse("A JSON request body is required");
    }
    return parsed;
  }
}

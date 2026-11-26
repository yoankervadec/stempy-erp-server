package com.lesconstructionssapete.stempyerpserver.platform.error;

import java.util.Map;
import java.util.Objects;

/**
 * One problem with one input field.
 *
 * @param field   path of the offending field as the client sent it, e.g.
 *                "lines[2].quantity"
 * @param code    stable machine-readable code, e.g. "MUST_BE_POSITIVE",
 *                "REQUIRED"
 * @param message human-readable explanation, in English; translated by code
 *                when the response is written (docs/ARCHITECTURE.md §23.5)
 * @param params  the values in the message ({"max": 50}), for translations
 *                and clients; empty when there are none
 */
public record FieldError(String field, String code, String message, Map<String, Object> params) {

  public FieldError {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    params = params == null ? Map.of() : Map.copyOf(params);
  }

  public FieldError(String field, String code, String message) {
    this(field, code, message, Map.of());
  }

  /** The same error with another message (a translation). */
  public FieldError withMessage(String message) {
    return new FieldError(field, code, message, params);
  }
}

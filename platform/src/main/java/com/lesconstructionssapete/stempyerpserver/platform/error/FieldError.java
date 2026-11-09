package com.lesconstructionssapete.stempyerpserver.platform.error;

import java.util.Objects;

/**
 * One problem with one input field.
 *
 * @param field   path of the offending field as the client sent it, e.g.
 *                "lines[2].quantity"
 * @param code    stable machine-readable code, e.g. "MUST_BE_POSITIVE",
 *                "REQUIRED"
 * @param message human-readable explanation
 */
public record FieldError(String field, String code, String message) {

  public FieldError {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }
}

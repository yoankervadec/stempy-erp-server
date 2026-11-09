package com.lesconstructionssapete.stempyerpserver.platform.error;

import java.util.List;

/**
 * The input is invalid. Carries per-field details so a UI can highlight each
 * problem.
 */
public class ValidationFailedException extends ErpException {

  private final List<FieldError> fieldErrors;

  public ValidationFailedException(String message, List<FieldError> fieldErrors) {
    super("VALIDATION_FAILED", message);
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  /** Request-level problem with no specific field. */
  public ValidationFailedException(String message) {
    this(message, List.of());
  }

  /** Convenience for the common single-field case. */
  public static ValidationFailedException field(String field, String code, String message) {
    return new ValidationFailedException("Validation failed", List.of(new FieldError(field, code, message)));
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }

  @Override
  public ErrorCategory category() {
    return ErrorCategory.VALIDATION;
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.error;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collects FieldErrors with the standard codes and messages, then throws them
 * all at once, so a client sees every problem in one response.
 *
 * <pre>
 * Validation.check()
 *     .required("userNo", userNo).maxLength("userNo", userNo, 50)
 *     .required("password", password)
 *     .throwIfInvalid();
 * </pre>
 *
 * At most one error per field: once a field has failed, later checks on it
 * are skipped (a missing value isn't also reported as too short). Checks
 * other than required() treat null as "nothing to check".
 */
public final class Validation {

  private final List<FieldError> errors;
  private final Set<String> failed = new HashSet<>();

  private Validation(List<FieldError> errors) {
    this.errors = errors;
    errors.forEach(e -> failed.add(e.field()));
  }

  public static Validation check() {
    return new Validation(new ArrayList<>());
  }

  /** Adds to a list another component is already collecting into. */
  public static Validation into(List<FieldError> errors) {
    return new Validation(errors);
  }

  // ---- checks ----

  /** Fails on null, an empty string or an empty char[]. */
  public Validation required(String field, Object value) {
    boolean missing = value == null
        || (value instanceof CharSequence s && s.isEmpty())
        || (value instanceof char[] c && c.length == 0);
    return missing ? add(field, "REQUIRED", "is required") : this;
  }

  public Validation maxLength(String field, CharSequence value, int max) {
    return value != null && value.length() > max
        ? add(field, "TOO_LONG", "must be at most " + max + " characters", Map.of("max", max))
        : this;
  }

  /** Also fails on null: use for values that must be present AND sized. */
  public Validation lengthBetween(String field, char[] value, int min, int max) {
    return value == null || value.length < min || value.length > max
        ? add(field, "INVALID_LENGTH", "must be " + min + " to " + max + " characters", Map.of("min", min, "max", max))
        : this;
  }

  public Validation matches(String field, CharSequence value, Pattern pattern) {
    return value != null && !pattern.matcher(value).matches()
        ? add(field, "INVALID_FORMAT", "has an invalid format")
        : this;
  }

  public Validation min(String field, BigDecimal value, BigDecimal min) {
    return value != null && value.compareTo(min) < 0
        ? add(field, "TOO_SMALL", "must be at least " + min.toPlainString(), Map.of("min", min))
        : this;
  }

  public Validation max(String field, BigDecimal value, BigDecimal max) {
    return value != null && value.compareTo(max) > 0
        ? add(field, "TOO_LARGE", "must be at most " + max.toPlainString(), Map.of("max", max))
        : this;
  }

  /** Trailing zeros don't count: 1.50 has one decimal. */
  public Validation maxScale(String field, BigDecimal value, int scale) {
    return value != null && value.stripTrailingZeros().scale() > scale
        ? add(field, "TOO_MANY_DECIMALS", "must have at most " + scale + " decimals", Map.of("scale", scale))
        : this;
  }

  /** A rule with no standard check; same one-error-per-field rule applies. */
  public Validation add(String field, String code, String message) {
    return add(field, code, message, Map.of());
  }

  /** As add(field, code, message), with the values the message names (for translations). */
  public Validation add(String field, String code, String message, Map<String, Object> params) {
    if (failed.add(field)) {
      errors.add(new FieldError(field, code, message, params));
    }
    return this;
  }

  // ---- outcome ----

  public boolean isValid() {
    return errors.isEmpty();
  }

  public List<FieldError> errors() {
    return List.copyOf(errors);
  }

  public void throwIfInvalid() {
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", errors);
    }
  }
}

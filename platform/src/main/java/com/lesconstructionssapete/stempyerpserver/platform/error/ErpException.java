package com.lesconstructionssapete.stempyerpserver.platform.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for expected, business-level failures that should reach the client
 * as a clear error
 * (as opposed to bugs and infrastructure failures, which become a generic 500).
 *
 * Every instance has:
 * - a category (what kind of failure; decides the HTTP status), supplied by the
 * subclass, and
 * - a stable, machine-readable code in UPPER_SNAKE_CASE, e.g.
 * "GL_PERIOD_CLOSED". Clients and
 * tests rely on the code, so treat it like part of the API: never reword it
 * casually.
 *
 * Modules extend one of the category classes rather than this one directly:
 *
 * <pre>
 * public class PeriodClosedException extends ConflictException {
 *   public PeriodClosedException(LocalDate date) {
 *     super("GL_PERIOD_CLOSED", "The posting period containing " + date + " is "
 *         + "closed");
 *   }
 * }
 * </pre>
 *
 * The message is shown to the client, so never put SQL, stack details or other
 * users' data in it.
 */
public abstract class ErpException extends RuntimeException {

  private final String code;
  private final Map<String, Object> params = new LinkedHashMap<>();

  protected ErpException(String code, String message) {
    super(message);
    this.code = requireValidCode(code);
  }

  protected ErpException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = requireValidCode(code);
  }

  public String code() {
    return code;
  }

  /**
   * Names a value the message contains, so a translation can show it too
   * (docs/ARCHITECTURE.md §23.5): {@code throw new XException(...).param("date", date)}.
   * A null value is left out.
   */
  public ErpException param(String name, Object value) {
    Objects.requireNonNull(name, "name");
    if (value != null) {
      params.put(name, value);
    }
    return this;
  }

  public Map<String, Object> params() {
    return Collections.unmodifiableMap(params);
  }

  public abstract ErrorCategory category();

  private static String requireValidCode(String code) {
    if (code == null || !code.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException(
          "Error code must be UPPER_SNAKE_CASE (e.g. GL_PERIOD_CLOSED), got: " + code);
    }
    return code;
  }
}

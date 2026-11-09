package com.lesconstructionssapete.stempyerpserver.platform.error;

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

  public abstract ErrorCategory category();

  private static String requireValidCode(String code) {
    if (code == null || !code.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException(
          "Error code must be UPPER_SNAKE_CASE (e.g. GL_PERIOD_CLOSED), got: " + code);
    }
    return code;
  }
}

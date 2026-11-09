package com.lesconstructionssapete.stempyerpserver.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErrorCategory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Decides what the client sees when something fails, and what gets logged. Pure
 * logic with no
 * Javalin types, so it is fully unit-testable. ExceptionMapper is the thin
 * Javalin adapter on top.
 *
 * Rules:
 * - ErpException: category picks the status; code, message and field errors go
 * to the client as-is.
 * - DataAccessException: retryable (deadlock, lock wait) -> 503 so clients can
 * retry; otherwise 500.
 * The SQL error is logged, never returned.
 * - Anything else: generic 500. The real exception is logged with its stack
 * trace; the client only
 * gets the request id, which is how you find it in the logs.
 *
 * Log levels: 5xx = error (with stack trace), 401/403 = warn, other 4xx = info.
 */
public final class ErrorTranslator {

  private static final Logger log = LoggerFactory.getLogger(ErrorTranslator.class);

  /** What to send back. */
  public record Translation(int status, ApiError body) {
  }

  public Translation translate(Throwable t, String requestId, String path) {
    if (t instanceof ErpException e) {
      return translateErp(e, requestId, path);
    }
    if (t instanceof DataAccessException e) {
      return translateDataAccess(e, requestId, path);
    }
    log.error("Unhandled exception [requestId={}, path={}]", requestId, path, t);
    return internalError(requestId, path);
  }

  /**
   * For failures raised by the web framework itself with a ready-made status (bad
   * JSON body,
   * wrong method, payload too large...). 4xx messages are passed through; 5xx are
   * replaced by
   * the generic internal error.
   */
  public Translation translateHttpStatus(int status, String message, String requestId, String path) {
    if (status >= 500) {
      log.error("Framework error {} [requestId={}, path={}]: {}", status, requestId, path, message);
      return internalError(requestId, path);
    }
    String title = titleFor(status);
    log.info("HTTP {} [requestId={}, path={}]: {}", status, requestId, path, message);
    String code = title.toUpperCase().replace(' ', '_');
    return new Translation(status, ApiError.of(status, title, code, message, path, requestId, null));
  }

  public static int statusFor(ErrorCategory category) {
    return switch (category) {
      case NOT_FOUND -> 404;
      case VALIDATION -> 422;
      case CONFLICT -> 409;
      case FORBIDDEN -> 403;
      case UNAUTHENTICATED -> 401;
    };
  }

  // ---- internals ----

  private Translation translateErp(ErpException e, String requestId, String path) {
    int status = statusFor(e.category());
    var fieldErrors = (e instanceof ValidationFailedException v) ? v.fieldErrors() : null;

    if (status == 401 || status == 403) {
      log.warn("{} [{}] [requestId={}, path={}]: {}", e.code(), status, requestId, path, e.getMessage());
    } else {
      log.info("{} [{}] [requestId={}, path={}]: {}", e.code(), status, requestId, path, e.getMessage());
    }
    return new Translation(status,
        ApiError.of(status, titleFor(status), e.code(), e.getMessage(), path, requestId, fieldErrors));
  }

  private Translation translateDataAccess(DataAccessException e, String requestId, String path) {
    if (e.isRetryable()) {
      log.warn("Retryable database failure [requestId={}, path={}]: {}", requestId, path, e.getMessage());
      return new Translation(503, ApiError.of(503, titleFor(503), "DATABASE_BUSY",
          "The system is busy. Please retry.", path, requestId, null));
    }
    log.error("Database failure [requestId={}, path={}]", requestId, path, e);
    return internalError(requestId, path);
  }

  private Translation internalError(String requestId, String path) {
    return new Translation(500, ApiError.of(500, titleFor(500), "INTERNAL_ERROR",
        "An unexpected error occurred.", path, requestId, null));
  }

  private static String titleFor(int status) {
    return switch (status) {
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 409 -> "Conflict";
      case 413 -> "Payload Too Large";
      case 415 -> "Unsupported Media Type";
      case 422 -> "Unprocessable Content";
      case 429 -> "Too Many Requests";
      case 500 -> "Internal Server Error";
      case 503 -> "Service Unavailable";
      default -> status >= 500 ? "Server Error" : "Client Error";
    };
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;

/**
 * The one error body the API ever returns: RFC 7807 "Problem Details" plus a
 * few extensions.
 * Send it with Content-Type: application/problem+json.
 *
 * Standard members: type, title, status, detail, instance.
 * Extensions: code (stable machine-readable error code), requestId (for support
 * / log lookup), errors (per-field problems, only for validation failures).
 *
 * Example:
 *
 * <pre>
 * <code class="json">
 * {
 *   "type": "urn:stempy:problem:gl_period_closed",
 *   "title": "Conflict",
 *   "status": 409,
 *   "detail": "The posting period containing 2026-03-14 is closed",
 *   "instance": "/gl/journals",
 *   "code": "GL_PERIOD_CLOSED",
 *   "requestId": "9f1c..."
 * }
 * </code>
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    String requestId,
    List<FieldError> errors) {

  public static final String CONTENT_TYPE = "application/problem+json";

  /**
   * Builds the error, deriving "type" from the code and dropping an empty errors
   * list.
   */
  public static ApiError of(int status, String title, String code, String detail,
      String instance, String requestId, List<FieldError> errors) {
    return new ApiError(
        "urn:stempy:problem:" + code.toLowerCase(),
        title,
        status,
        detail,
        instance,
        code,
        requestId,
        (errors == null || errors.isEmpty()) ? null : List.copyOf(errors));
  }
}

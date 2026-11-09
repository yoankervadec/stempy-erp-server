package com.lesconstructionssapete.stempyerpserver.platform.error;

/**
 * HTTP-agnostic classification of a business failure. Operations and
 * orchestrators throw exceptions that carry one of these; only the web layer's
 * ExceptionMapper turns a category into a status code. That keeps business code
 * free of any HTTP knowledge.
 */
public enum ErrorCategory {
  /** The thing asked for doesn't exist (or isn't visible to this company). */
  NOT_FOUND,
  /**
   * The input is invalid: missing/malformed fields, rule violations on the
   * request itself.
   */
  VALIDATION,
  /**
   * The request is valid but clashes with current state: wrong status, closed
   * period, stale version.
   */
  CONFLICT,
  /** The caller is known but not allowed to do this. */
  FORBIDDEN,
  /** The caller is not (validly) logged in. */
  UNAUTHENTICATED
}

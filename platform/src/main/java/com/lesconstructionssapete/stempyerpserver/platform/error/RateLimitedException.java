package com.lesconstructionssapete.stempyerpserver.platform.error;

/** Too many attempts; the client should wait before retrying (HTTP 429). */
public class RateLimitedException extends ErpException {

  public RateLimitedException(String code, String message) {
    super(code, message);
  }

  @Override
  public ErrorCategory category() {
    return ErrorCategory.RATE_LIMITED;
  }
}

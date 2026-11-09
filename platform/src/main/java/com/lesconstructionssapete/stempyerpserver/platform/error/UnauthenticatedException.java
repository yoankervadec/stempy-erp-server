package com.lesconstructionssapete.stempyerpserver.platform.error;

/**
 * No valid credentials: missing, expired or malformed token, wrong password.
 * (Named "Unauthenticated" because HTTP's 401 "Unauthorized" really means
 * this.)
 */
public class UnauthenticatedException extends ErpException {

  public UnauthenticatedException(String code, String message) {
    super(code, message);
  }

  public UnauthenticatedException(String code, String message, Throwable cause) {
    super(code, message, cause);
  }

  @Override
  public ErrorCategory category() {
    return ErrorCategory.UNAUTHENTICATED;
  }
}

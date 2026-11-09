package com.lesconstructionssapete.stempyerpserver.platform.error;

/**
 * Authenticated, but not permitted. Thrown by PermissionChecker, the single
 * permission choke point.
 */
public class ForbiddenException extends ErpException {

  public ForbiddenException(String code, String message) {
    super(code, message);
  }

  /** The usual case: a named permission is missing. */
  public static ForbiddenException missingPermission(String permission) {
    return new ForbiddenException("PERMISSION_DENIED", "Missing permission: " + permission);
  }

  @Override
  public ErrorCategory category() {
    return ErrorCategory.FORBIDDEN;
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.error;

/**
 * The requested record doesn't exist, or doesn't belong to the caller's
 * company.
 * Deliberately the same answer in both cases, so one company can't probe for
 * another's IDs.
 */
public class ResourceNotFoundException extends ErpException {

  public ResourceNotFoundException(String code, String message) {
    super(code, message);
  }

  /** Generic form: {@code ResourceNotFoundException.of("SalesOrder", 42)} */
  public static ResourceNotFoundException of(String resource, Object id) {
    return new ResourceNotFoundException("RESOURCE_NOT_FOUND", resource + " " + id + " not found");
  }

  @Override
  public ErrorCategory category() {
    return ErrorCategory.NOT_FOUND;
  }
}

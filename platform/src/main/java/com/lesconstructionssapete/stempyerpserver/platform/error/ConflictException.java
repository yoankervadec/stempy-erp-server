package com.lesconstructionssapete.stempyerpserver.platform.error;

/**
 * Valid request, wrong moment: the order is already posted, the period is
 * closed, the record was changed by someone else (optimistic-lock failure), a
 * duplicate idempotency key is in flight.
 * The client may succeed later or after refreshing its data.
 */
public class ConflictException extends ErpException {

  public ConflictException(String code, String message) {
    super(code, message);
  }

  public ConflictException(String code, String message, Throwable cause) {
    super(code, message, cause);
  }

  @Override
  public ErrorCategory category() {
    return ErrorCategory.CONFLICT;
  }
}

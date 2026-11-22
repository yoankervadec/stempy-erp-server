package com.lesconstructionssapete.stempyerpserver.platform.error;

/** The request is malformed outside its body, e.g. an invalid header (400). */
public class BadRequestException extends ErpException {

  public BadRequestException(String code, String message) {
    super(code, message);
  }

  @Override
  public ErrorCategory category() {
    return ErrorCategory.BAD_REQUEST;
  }
}

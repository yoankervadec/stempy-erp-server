package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

/** POST /auth/login body. */
public record LoginRequest(String userNo, String password) {

  /** Never print the password, e.g. if this ends up in a log line. */
  @Override
  public String toString() {
    return "LoginRequest[userNo=" + userNo + "]";
  }
}

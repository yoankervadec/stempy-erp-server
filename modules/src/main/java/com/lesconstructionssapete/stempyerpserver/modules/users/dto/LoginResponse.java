package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

import java.time.Instant;

/**
 * POST /auth/login result. The client sends {@code token} back as
 * "Authorization: Bearer &lt;token&gt;". It is shown only here, once.
 *
 * @param expiresAt          the latest the session can last, even if used
 * @param idleTimeoutSeconds the session also ends after this much inactivity
 */
public record LoginResponse(String token, Instant expiresAt, long idleTimeoutSeconds, UserProfile user) {

  @Override
  public String toString() {
    return "LoginResponse[expiresAt=" + expiresAt + ", user=" + user + "]";
  }
}

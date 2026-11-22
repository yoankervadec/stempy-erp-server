package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

import java.time.Instant;
import java.util.List;

/**
 * POST /auth/login result. The client sends {@code token} back as
 * "Authorization: Bearer &lt;token&gt;". It is shown only here, once.
 *
 * @param expiresAt          the latest the session can last, even if used
 * @param idleTimeoutSeconds the session also ends after this much inactivity
 * @param companies          the companies the user may work in; the client
 *                           picks one per request with X-Company-Id
 * @param defaultCompanyId   used when a request sends no X-Company-Id; null if
 *                           the user has several companies and no valid
 *                           default (the header is then required)
 */
public record LoginResponse(String token, Instant expiresAt, long idleTimeoutSeconds, UserProfile user,
    List<CompanySummary> companies, Long defaultCompanyId) {

  @Override
  public String toString() {
    return "LoginResponse[expiresAt=" + expiresAt + ", user=" + user + "]";
  }
}

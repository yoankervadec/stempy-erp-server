package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Optional;

/**
 * Resolves a session token to the caller. Implemented by the users module
 * (which owns the sessions table) and published in the ServiceRegistry; the
 * platform's AuthFilter only depends on this interface.
 *
 * A session identifies a user only: the company is chosen per request (see
 * AuthFilter), and permissions depend on that company (CompanyAccess).
 */
public interface SessionAuthenticator {

  /**
   * The caller behind {@code token}, or empty if the token is unknown, expired,
   * idle too long, revoked, or its user is disabled. Called on every
   * authenticated request.
   */
  Optional<Principal> authenticate(String token);

  record Principal(long userId) {
  }
}

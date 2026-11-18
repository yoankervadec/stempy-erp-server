package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a session token to the caller. Implemented by the users module
 * (which owns the sessions table) and published in the ServiceRegistry; the
 * platform's AuthFilter only depends on this interface.
 */
public interface SessionAuthenticator {

  /**
   * The caller behind {@code token}, or empty if the token is unknown, expired,
   * idle too long, revoked, or its user/company is disabled. Called on every
   * authenticated request.
   */
  Optional<Principal> authenticate(String token);

  record Principal(long userId, long companyId, Set<String> permissions) {
    public Principal {
      permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
  }
}

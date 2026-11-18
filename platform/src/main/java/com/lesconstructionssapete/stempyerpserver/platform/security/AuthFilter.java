package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Objects;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator.Principal;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;

import io.javalin.http.Context;

/**
 * Before-handler that turns "Authorization: Bearer &lt;session token&gt;" into
 * the request's RequestContext. Installed by WebServer right after
 * RequestIdFilter.
 *
 * - No Authorization header: the request stays anonymous. Public routes
 * (/health, /auth/login) work; routes that call RequestContexts.require get
 * 401.
 * - A header that is not a Bearer token, or a token that doesn't resolve: 401
 * immediately, even on public routes. A client sending a bad token has a bug
 * or a stale session and should hear about it.
 */
public final class AuthFilter {

  public static final String HEADER = "Authorization";
  private static final String SCHEME = "Bearer ";

  private final SessionAuthenticator authenticator;

  public AuthFilter(SessionAuthenticator authenticator) {
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
  }

  public void handle(Context ctx) {
    Optional<String> token = bearerToken(ctx);
    if (token.isEmpty()) {
      return;
    }
    Principal p = authenticator.authenticate(token.get())
        .orElseThrow(() -> new UnauthenticatedException("SESSION_INVALID", "The session is invalid or has expired"));
    RequestContexts.set(ctx, new RequestContext(
        RequestContexts.requestId(ctx), p.userId(), p.companyId(), p.permissions()));
  }

  /**
   * The bearer token, or empty if no Authorization header was sent. Throws 401
   * if the header is present but malformed. Also used by logout to find the
   * session to revoke.
   */
  public static Optional<String> bearerToken(Context ctx) {
    String header = ctx.header(HEADER);
    if (header == null) {
      return Optional.empty();
    }
    if (header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
      String token = header.substring(SCHEME.length()).trim();
      if (!token.isEmpty()) {
        return Optional.of(token);
      }
    }
    throw new UnauthenticatedException("AUTH_HEADER_INVALID", "Authorization header must be 'Bearer <token>'");
  }
}

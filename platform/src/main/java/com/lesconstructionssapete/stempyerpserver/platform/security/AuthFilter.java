package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ForbiddenException;
import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator.Principal;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;

import io.javalin.http.Context;

/**
 * Before-handler that turns "Authorization: Bearer &lt;session token&gt;" into
 * the request's caller. Installed by WebServer right after RequestIdFilter.
 *
 * - No Authorization header: the request stays anonymous. Public routes
 * (/health, /auth/login) work; routes that call RequestContexts.require get
 * 401.
 * - A header that is not a Bearer token, or a token that doesn't resolve: 401
 * immediately, even on public routes. A client sending a bad token has a bug
 * or a stale session and should hear about it.
 *
 * The company is chosen per request, when a route first calls
 * RequestContexts.require (so two browser tabs can work in two companies):
 *
 * - X-Company-Id header: must be a company the user may work in, else 403
 * COMPANY_ACCESS_DENIED; not a positive number: 400 INVALID_COMPANY_ID.
 * - No header: CompanyAccess.defaultCompany (the user's default, else their
 * only company), else 400 COMPANY_REQUIRED.
 */
public final class AuthFilter {

  public static final String HEADER = "Authorization";
  public static final String COMPANY_HEADER = "X-Company-Id";
  private static final String SCHEME = "Bearer ";

  private final SessionAuthenticator authenticator;
  private final CompanyAccess access;

  public AuthFilter(SessionAuthenticator authenticator, CompanyAccess access) {
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    this.access = Objects.requireNonNull(access, "access");
  }

  public void handle(Context ctx) {
    Optional<String> token = bearerToken(ctx);
    if (token.isEmpty()) {
      return;
    }
    Principal p = authenticator.authenticate(token.get())
        .orElseThrow(() -> new UnauthenticatedException("SESSION_INVALID", "The session is invalid or has expired"));
    RequestContexts.setCaller(ctx, p.userId(), () -> resolve(ctx, p.userId()));
  }

  private RequestContext resolve(Context ctx, long userId) {
    long companyId = requestedCompany(ctx)
        .orElseGet(() -> {
          var fallback = access.defaultCompany(userId);
          if (fallback.isEmpty()) {
            throw new BadRequestException("COMPANY_REQUIRED",
                "Several companies are available; choose one with the " + COMPANY_HEADER + " header");
          }
          return fallback.getAsLong();
        });
    Set<String> permissions = access.permissions(userId, companyId)
        .orElseThrow(() -> new ForbiddenException("COMPANY_ACCESS_DENIED",
            "No access to company " + companyId));
    Language language = access.language(userId, companyId).orElse(Language.DEFAULT);
    return new RequestContext(RequestContexts.requestId(ctx), userId, companyId, permissions, language);
  }

  private static Optional<Long> requestedCompany(Context ctx) {
    String raw = ctx.header(COMPANY_HEADER);
    if (raw == null) {
      return Optional.empty();
    }
    try {
      long id = Long.parseLong(raw.trim());
      if (id > 0) {
        return Optional.of(id);
      }
    } catch (NumberFormatException ignored) {
      // reported below
    }
    throw new BadRequestException("INVALID_COMPANY_ID", COMPANY_HEADER + " must be a company id");
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

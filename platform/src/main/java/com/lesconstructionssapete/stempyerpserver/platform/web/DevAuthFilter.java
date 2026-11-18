package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;

import io.javalin.http.Context;

/**
 * DEVELOPMENT ONLY stand-in for the JWT AuthFilter: trusts whatever identity
 * the caller claims in plain headers. Anyone who can reach the server can act
 * as any user of any company, so it must never be enabled in production. Main
 * installs it only when DEV_AUTH_ENABLED=true, and it logs a warning when
 * built.
 *
 * Headers:
 * - X-Dev-User-Id: users.id (required to authenticate)
 * - X-Dev-Company-Id: the company to operate on (required with the user id)
 * - X-Dev-Permissions: optional comma-separated permission names
 *
 * No X-Dev-User-Id header: the request stays unauthenticated (routes that call
 * RequestContexts.require get 401, public routes like /health still work).
 * Malformed headers: 401 right away.
 *
 * Example:
 *
 * <pre>
 * curl -X QUERY localhost:8081/masterdata/products \
 *   -H 'X-Dev-User-Id: 1' -H 'X-Dev-Company-Id: 1' \
 *   -H 'Content-Type: application/json' \
 *   -d '{"where":{"field":"name","op":"contains","value":"bolt"}}'
 * </pre>
 */
public final class DevAuthFilter {

  private static final Logger log = LoggerFactory.getLogger(DevAuthFilter.class);

  public static final String USER_HEADER = "X-Dev-User-Id";
  public static final String COMPANY_HEADER = "X-Dev-Company-Id";
  public static final String PERMISSIONS_HEADER = "X-Dev-Permissions";

  public DevAuthFilter() {
    log.warn("DevAuthFilter is ENABLED: requests are authenticated from {} / {} headers without any check. "
        + "Never use this outside local development.", USER_HEADER, COMPANY_HEADER);
  }

  public void handle(Context ctx) {
    String user = ctx.header(USER_HEADER);
    if (user == null) {
      return;
    }
    long userId = parseId(user, USER_HEADER);
    long companyId = parseId(ctx.header(COMPANY_HEADER), COMPANY_HEADER);
    RequestContexts.set(ctx, new RequestContext(
        RequestContexts.requestId(ctx), userId, companyId, permissions(ctx.header(PERMISSIONS_HEADER))));
  }

  private static long parseId(String raw, String header) {
    try {
      long id = Long.parseLong(raw == null ? "" : raw.trim());
      if (id > 0) {
        return id;
      }
    } catch (NumberFormatException ignored) {
      // fall through to the error below
    }
    throw new UnauthenticatedException("DEV_AUTH_INVALID", header + " must be a positive integer");
  }

  private static Set<String> permissions(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
}

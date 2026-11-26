package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;

import io.javalin.http.Context;

/**
 * TEST ONLY: trusts whatever identity the caller claims in plain headers, so
 * web-layer tests can exercise authenticated routes without sessions. Lives in
 * test sources so it can never be enabled in a real deployment.
 *
 * Headers: X-Dev-User-Id and X-Dev-Company-Id (both required to authenticate),
 * X-Dev-Permissions (optional, comma-separated), X-Dev-Language (optional:
 * fr, en; default Language.DEFAULT). No X-Dev-User-Id: the request
 * stays unauthenticated. Malformed: 401.
 */
public final class DevAuthFilter {

  public static final String USER_HEADER = "X-Dev-User-Id";
  public static final String COMPANY_HEADER = "X-Dev-Company-Id";
  public static final String PERMISSIONS_HEADER = "X-Dev-Permissions";
  public static final String LANGUAGE_HEADER = "X-Dev-Language";

  public void handle(Context ctx) {
    String user = ctx.header(USER_HEADER);
    if (user == null) {
      return;
    }
    long userId = parseId(user, USER_HEADER);
    long companyId = parseId(ctx.header(COMPANY_HEADER), COMPANY_HEADER);
    RequestContexts.set(ctx, new RequestContext(
        RequestContexts.requestId(ctx), userId, companyId, permissions(ctx.header(PERMISSIONS_HEADER)),
        Language.fromCode(ctx.header(LANGUAGE_HEADER)).orElse(Language.DEFAULT)));
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

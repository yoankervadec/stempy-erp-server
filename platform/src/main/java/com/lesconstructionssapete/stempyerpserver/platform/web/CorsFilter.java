package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.List;
import java.util.Set;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;

/**
 * Lets the configured origins call the API from a browser page served
 * elsewhere (docs/ARCHITECTURE.md §23.10): the desktop client's renderer, a
 * dev server. Runs before authentication.
 *
 * - A request from an allowed origin gets Access-Control-Allow-Origin (that
 * origin, never "*") and the response headers a client reads (ETag,
 * Location...) exposed. Credentials travel in the Authorization header, not
 * cookies, so no Allow-Credentials.
 * - A preflight (OPTIONS with Access-Control-Request-Method) from an allowed
 * origin is answered here, 204, for the API's methods (QUERY included) and
 * request headers; nothing else runs.
 * - Any other origin gets no CORS header at all: the browser blocks it. The
 * request itself still runs (it is the browser that enforces CORS), so this
 * is no security boundary: authentication is.
 */
final class CorsFilter {

  static final String ALLOW_METHODS = "GET, POST, PUT, PATCH, DELETE, QUERY";
  /** Request headers the API reads. */
  static final String ALLOW_HEADERS = "Authorization, Content-Type, X-Company-Id, Idempotency-Key, Accept-Language,"
      + " If-None-Match, X-Request-Id";
  static final String EXPOSE_HEADERS = "ETag, Location, X-Request-Id, Idempotent-Replayed";
  /** Seconds a browser may reuse a preflight answer. */
  static final String MAX_AGE = "600";

  private final Set<String> origins;

  CorsFilter(List<String> origins) {
    this.origins = Set.copyOf(origins);
  }

  void handle(Context ctx) {
    String origin = ctx.header("Origin");
    if (origin == null || !origins.contains(origin)) {
      return;
    }
    ctx.header("Access-Control-Allow-Origin", origin);
    ctx.header("Vary", "Origin");
    if (ctx.method() == HandlerType.OPTIONS && ctx.header("Access-Control-Request-Method") != null) {
      ctx.header("Access-Control-Allow-Methods", ALLOW_METHODS);
      ctx.header("Access-Control-Allow-Headers", ALLOW_HEADERS);
      ctx.header("Access-Control-Max-Age", MAX_AGE);
      ctx.status(204);
      ctx.skipRemainingHandlers();
      return;
    }
    ctx.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
  }
}

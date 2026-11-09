package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;

import io.javalin.http.Context;

/**
 * Stores and fetches per-request data on Javalin's request attributes, so no
 * other class needs to know the attribute keys (or that Javalin is involved at
 * all).
 *
 * RequestIdFilter -> setRequestId(ctx, id) first thing on every request
 * AuthFilter -> set(ctx, requestContext) after the JWT is validated
 * Controllers -> require(ctx) then pass the RequestContext down explicitly
 * ExceptionMapper -> requestId(ctx) works even if authentication failed
 *
 * NOTE: uses ctx.attribute(key) / ctx.attribute(key, value), per the Javalin 7
 * docs.
 */
public final class RequestContexts {

  private static final String REQUEST_ID_KEY = "stempy.requestId";
  private static final String REQUEST_CONTEXT_KEY = "stempy.requestContext";

  /**
   * Shown when the request id was never set, e.g. the failure happened before
   * RequestIdFilter ran.
   */
  public static final String UNKNOWN_REQUEST_ID = "unknown";

  private RequestContexts() {
  }

  public static void setRequestId(Context ctx, String requestId) {
    ctx.attribute(REQUEST_ID_KEY, requestId);
  }

  /** Never null and never throws, so it is safe to call from error handling. */
  public static String requestId(Context ctx) {
    Object value = ctx.attribute(REQUEST_ID_KEY);
    return (value instanceof String s && !s.isBlank()) ? s : UNKNOWN_REQUEST_ID;
  }

  public static void set(Context ctx, RequestContext requestContext) {
    ctx.attribute(REQUEST_CONTEXT_KEY, requestContext);
  }

  public static Optional<RequestContext> find(Context ctx) {
    Object value = ctx.attribute(REQUEST_CONTEXT_KEY);
    return (value instanceof RequestContext rc) ? Optional.of(rc) : Optional.empty();
  }

  /**
   * The authenticated caller's context. Throws UnauthenticatedException (-> 401)
   * if the route
   * wasn't behind the auth filter or authentication didn't happen; a controller
   * never sees null.
   */
  public static RequestContext require(Context ctx) {
    return find(ctx).orElseThrow(() -> new UnauthenticatedException("UNAUTHENTICATED", "Authentication required"));
  }
}

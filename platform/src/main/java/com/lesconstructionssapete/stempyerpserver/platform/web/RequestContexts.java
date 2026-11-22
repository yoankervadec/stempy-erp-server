package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Optional;
import java.util.function.Supplier;

import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;

import io.javalin.http.Context;

/**
 * Stores and fetches per-request data on Javalin's request attributes, so no
 * other class needs to know the attribute keys (or that Javalin is involved at
 * all).
 *
 * <pre>
 * RequestIdFilter -> setRequestId(ctx, id)              first thing on every request
 * AuthFilter      -> setCaller(ctx, userId, resolver)   once the session token is valid
 * Controllers     -> require(ctx)                       the full context: user + company + permissions
 *                    requireUserId(ctx)                 just "who is logged in" (logout)
 * ExceptionMapper -> requestId(ctx)                     works even if authentication failed
 * </pre>
 *
 * The company (and so the permissions) is resolved the first time require()
 * is called, then kept for the rest of the request: routes that don't need a
 * company (logout, health) never fail on company selection.
 */
public final class RequestContexts {

  private static final String REQUEST_ID_KEY = "stempy.requestId";
  private static final String USER_ID_KEY = "stempy.userId";
  private static final String RESOLVER_KEY = "stempy.requestContextResolver";
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

  /**
   * The caller is authenticated; {@code resolver} builds the full context
   * (company selection, permissions) if and when a route needs it. It may throw
   * (400 / 403), and runs at most once per request.
   */
  public static void setCaller(Context ctx, long userId, Supplier<RequestContext> resolver) {
    ctx.attribute(USER_ID_KEY, userId);
    ctx.attribute(RESOLVER_KEY, resolver);
  }

  /** An already-resolved context, e.g. from a test or development filter. */
  public static void set(Context ctx, RequestContext requestContext) {
    ctx.attribute(USER_ID_KEY, requestContext.userId());
    ctx.attribute(REQUEST_CONTEXT_KEY, requestContext);
  }

  /**
   * The authenticated user, without resolving a company. 401 if the request
   * carries no valid session.
   */
  public static long requireUserId(Context ctx) {
    if (ctx.attribute(USER_ID_KEY) instanceof Long userId) {
      return userId;
    }
    throw unauthenticated();
  }

  /**
   * The authenticated caller's full context. 401 without a valid session; the
   * resolver's error (400 / 403) if the company can't be determined or isn't
   * accessible. A controller never sees null.
   */
  public static RequestContext require(Context ctx) {
    if (ctx.attribute(REQUEST_CONTEXT_KEY) instanceof RequestContext rc) {
      return rc;
    }
    if (ctx.attribute(RESOLVER_KEY) instanceof Supplier<?> resolver) {
      RequestContext rc = (RequestContext) resolver.get();
      ctx.attribute(REQUEST_CONTEXT_KEY, rc);
      return rc;
    }
    throw unauthenticated();
  }

  /** The full context if it was already resolved; never resolves or throws. */
  public static Optional<RequestContext> find(Context ctx) {
    return ctx.attribute(REQUEST_CONTEXT_KEY) instanceof RequestContext rc ? Optional.of(rc) : Optional.empty();
  }

  private static UnauthenticatedException unauthenticated() {
    return new UnauthenticatedException("UNAUTHENTICATED", "Authentication required");
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.web;

import org.slf4j.MDC;

import io.javalin.http.Context;

/**
 * Gives every request an id before anything else runs: reads or generates it,
 * stores it on the request (RequestContexts), echoes it in the X-Request-Id
 * response header, and puts it in the logging MDC as "requestId".
 *
 * The MDC only shows up in log lines once the logging backend prints it.
 * slf4j-simple does not; logback-classic does (pattern %X{requestId}). Until
 * then this is harmless.
 *
 * Registered by WebServer as the first before-handler and as an after-handler
 * (to clear the MDC).
 */
public final class RequestIdFilter {

  public static final String HEADER = "X-Request-Id";
  private static final String MDC_KEY = "requestId";

  public void before(Context ctx) {
    String requestId = RequestIds.resolve(ctx.header(HEADER));
    RequestContexts.setRequestId(ctx, requestId);
    ctx.header(HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
  }

  public void after(Context ctx) {
    MDC.remove(MDC_KEY);
  }
}

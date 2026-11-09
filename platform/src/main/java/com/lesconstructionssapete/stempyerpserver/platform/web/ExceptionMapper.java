package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator.Translation;

import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;

/**
 * Javalin adapter for ErrorTranslator: runs the translation and writes the
 * problem+json response.
 * All the decisions live in ErrorTranslator; this class only touches Javalin.
 *
 * Register it ONCE, as the catch-all, in WebServer:
 *
 * config.routes.exception(Exception.class, exceptionMapper::handle);
 *
 * (Javalin 7 docs disagree on the exact spelling, config.routes.exception(...)
 * vs
 * config.error.exception(...); use whichever compiles. The handler itself is
 * the same.)
 *
 * Because it is registered for Exception.class it also receives Javalin's own
 * HttpResponseException (BadRequestResponse, NotFoundResponse, ...), which is
 * why those are handled explicitly below.
 *
 */
public final class ExceptionMapper {

  private final ErrorTranslator translator;

  public ExceptionMapper(ErrorTranslator translator) {
    this.translator = Objects.requireNonNull(translator, "translator");
  }

  public void handle(Exception e, Context ctx) {
    String requestId = RequestContexts.requestId(ctx);
    String path = ctx.path();

    Translation t = (e instanceof HttpResponseException http)
        ? translator.translateHttpStatus(http.getStatus(), http.getMessage(), requestId, path)
        : translator.translate(e, requestId, path);

    ctx.status(t.status());
    ctx.json(t.body());
    // json() sets application/json, so the problem+json type must be applied AFTER
    // it.
    ctx.contentType(ApiError.CONTENT_TYPE);
    ctx.header("X-Request-Id", requestId);
  }
}

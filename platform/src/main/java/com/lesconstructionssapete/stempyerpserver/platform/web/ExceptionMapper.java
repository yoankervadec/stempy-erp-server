package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Map;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
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
  private final ErrorLocalizer localizer;

  /** Errors in English only. */
  public ExceptionMapper(ErrorTranslator translator) {
    this(translator, Messages.empty());
  }

  /** Errors translated into the request's language (docs/ARCHITECTURE.md §23.5). */
  public ExceptionMapper(ErrorTranslator translator, Messages messages) {
    this.translator = Objects.requireNonNull(translator, "translator");
    this.localizer = new ErrorLocalizer(messages);
  }

  public void handle(Exception e, Context ctx) {
    String requestId = RequestContexts.requestId(ctx);
    String path = ctx.path();

    Translation t = (e instanceof HttpResponseException http)
        ? translator.translateHttpStatus(http.getStatus(), http.getMessage(), requestId, path)
        : translator.translate(e, requestId, path);

    Map<String, Object> params = e instanceof ErpException erp ? erp.params() : Map.of();
    ApiError body = localizer.localize(t.body(), language(ctx), params);
    if (e instanceof ErpException erp && erp.current() != null) {
      body = body.withCurrent(erp.current());
    }

    ctx.status(t.status());
    ctx.json(body);
    // json() sets application/json, so the problem+json type must be applied AFTER
    // it.
    ctx.contentType(ApiError.CONTENT_TYPE);
    ctx.header("X-Request-Id", requestId);
  }

  /**
   * The resolved caller's language; before that (login, 401, a company that
   * can't be chosen), the request's Accept-Language; else the default.
   */
  private static Language language(Context ctx) {
    return RequestContexts.find(ctx).map(RequestContext::language)
        .or(() -> Language.fromAcceptLanguage(ctx.header("Accept-Language")))
        .orElse(Language.DEFAULT);
  }
}

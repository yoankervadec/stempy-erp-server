package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.json.JavalinJackson;

/**
 * Builds, starts and stops Javalin.
 *
 * This is the only class that knows how Javalin is assembled. **Everything
 * cross-cutting is installed here, in this order:
 *
 * 1. JSON mapper (JsonMapperFactory)
 * 2. RequestIdFilter (before every request, MDC cleanup after)
 * 3. CorsFilter, if origins are configured (answers preflights itself)
 * 4. the authentication filter, if one was given (builds the RequestContext)
 * 5. ExceptionMapper as the catch-all exception handler
 * 6. the routes, supplied by the caller through a Consumer<Routes>
 *
 */
public final class WebServer {

  private static final Logger log = LoggerFactory.getLogger(WebServer.class);

  private final WebServerConfig settings;
  private final ExceptionMapper exceptionMapper;
  private final Handler authFilter; // null = no authentication filter installed
  private final RequestIdFilter requestIdFilter = new RequestIdFilter();

  private Javalin app;

  /** No authentication filter: every RequestContexts.require(ctx) yields 401. */
  public WebServer(WebServerConfig settings, ExceptionMapper exceptionMapper) {
    this(settings, exceptionMapper, null);
  }

  /**
   * @param authFilter before-handler that calls RequestContexts.set(ctx, ...)
   *                   for authenticated callers. It should leave the context
   *                   unset (not throw) when no credentials are sent, so public
   *                   routes such as /health keep working.
   */
  public WebServer(WebServerConfig settings, ExceptionMapper exceptionMapper, Handler authFilter) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper");
    this.authFilter = authFilter;
  }

  public synchronized void start(Consumer<Routes> routeRegistrar) {
    if (app != null) {
      throw new IllegalStateException("WebServer is already started");
    }

    Javalin created = Javalin.create(config -> {
      config.jetty.host = settings.host();
      config.jetty.port = settings.port();

      config.jsonMapper(new JavalinJackson().updateMapper(JsonMapperFactory::configure));

      config.routes.before(requestIdFilter::before);
      config.routes.after(requestIdFilter::after);
      if (!settings.corsOrigins().isEmpty()) {
        config.routes.before(new CorsFilter(settings.corsOrigins())::handle);
      }
      if (authFilter != null) {
        config.routes.before(authFilter);
      }
      config.routes.exception(Exception.class, exceptionMapper::handle);

      routeRegistrar.accept(new Routes(config));
    });

    created.start();
    app = created; // only remembered once start() succeeded, so a failed start can be retried
    log.info("Web server listening on {}:{}", settings.host(), created.port());
  }

  /**
   * The port actually bound. Differs from the configured one when that was 0
   * (pick any free port), which is what tests use.
   */
  public synchronized int port() {
    if (app == null) {
      throw new IllegalStateException("WebServer is not started");
    }
    return app.port();
  }

  public synchronized void stop() {
    if (app != null) {
      app.stop();
      app = null;
      log.info("Web server stopped");
    }
  }
}

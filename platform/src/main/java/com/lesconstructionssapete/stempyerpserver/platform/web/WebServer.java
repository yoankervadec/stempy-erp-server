package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

/**
 * Builds, starts and stops Javalin.
 *
 * This is the only class that knows how Javalin is assembled. **Everything
 * cross-cutting is installed here, in this order:
 *
 * 1. JSON mapper (JsonMapperFactory)
 * 2. RequestIdFilter (before every request, MDC cleanup after)
 * 3. ExceptionMapper as the catch-all exception handler
 * 4. the routes, supplied by the caller through a Consumer<Routes>
 *
 * Javalin 7 wants routes registered while the server is being created, not
 * afterwards, hence the Consumer: Main passes "register every module's routes"
 * and WebServer runs it at the right moment.
 *
 */
public final class WebServer {

  private static final Logger log = LoggerFactory.getLogger(WebServer.class);

  private final WebServerConfig settings;
  private final ExceptionMapper exceptionMapper;
  private final RequestIdFilter requestIdFilter = new RequestIdFilter();

  private Javalin app;

  public WebServer(WebServerConfig settings, ExceptionMapper exceptionMapper) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper");
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
      // Docs disagree on the spelling: config.routes.exception(...) (migration guide)
      // vs config.error.exception(...)
      config.routes.exception(Exception.class, exceptionMapper::handle);

      routeRegistrar.accept(new Routes(config));
    });

    created.start();
    app = created; // only remembered once start() succeeded, so a failed start can be retried
    log.info("Web server listening on {}:{}", settings.host(), settings.port());
  }

  public synchronized void stop() {
    if (app != null) {
      app.stop();
      app = null;
      log.info("Web server stopped");
    }
  }
}

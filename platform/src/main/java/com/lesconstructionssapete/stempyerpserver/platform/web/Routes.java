package com.lesconstructionssapete.stempyerpserver.platform.web;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/**
 * What a module sees when it registers its endpoints: a few verbs and nothing
 * else.
 *
 */
public final class Routes {

  private final JavalinConfig config;

  Routes(JavalinConfig config) {
    this.config = config;
  }

  public Routes get(String path, Handler handler) {
    config.routes.get(path, handler);
    return this;
  }

  // Javalin 7+
  public Routes query(String path, Handler handler) {
    config.routes.query(path, handler);
    return this;
  }

  public Routes post(String path, Handler handler) {
    config.routes.post(path, handler);
    return this;
  }

  public Routes put(String path, Handler handler) {
    config.routes.put(path, handler);
    return this;
  }

  public Routes patch(String path, Handler handler) {
    config.routes.patch(path, handler);
    return this;
  }

  public Routes delete(String path, Handler handler) {
    config.routes.delete(path, handler);
    return this;
  }
}

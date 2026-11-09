package com.lesconstructionssapete.stempyerpserver.platform.web;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/**
 * What a module sees when it registers its endpoints: a few verbs and nothing
 * else.
 *
 * Why not hand modules Javalin directly? Javalin 7 moved route registration
 * into the Javalin.create(config -> ...) block, which changed how every module
 * would have to register routes.
 * Putting this thin wrapper in between means the next Javalin change is fixed
 * here, once, instead of in every module. It is also the natural place for
 * later additions such as permission-guarded overloads:
 * routes.post("/gl/journals", "gl.post", handler).
 *
 * Handlers are Javalin's own Handler (ctx -> ...), so controllers still use
 * Context directly.
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

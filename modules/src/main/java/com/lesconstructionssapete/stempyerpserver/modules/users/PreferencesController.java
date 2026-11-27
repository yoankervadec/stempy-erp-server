package com.lesconstructionssapete.stempyerpserver.modules.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * The signed-in user's own preferences (docs/ARCHITECTURE.md §27). Signed in
 * is enough; no company is resolved, so X-Company-Id is ignored.
 *
 * GET    /auth/me/preferences        -> 200 {items: [{key, value, updatedAt}]}  (none: [], never 404)
 * PUT    /auth/me/preferences/{key}  {"value": any JSON but null} -> 200 {key, value, updatedAt}
 * DELETE /auth/me/preferences/{key}  -> 204, also when the key didn't exist
 */
public class PreferencesController {

  private final PreferencesService preferences;

  public PreferencesController(PreferencesService preferences) {
    this.preferences = preferences;
  }

  public void register(Routes routes) {
    routes.get("/auth/me/preferences", this::list);
    routes.put("/auth/me/preferences/{key}", this::put);
    routes.delete("/auth/me/preferences/{key}", this::delete);
  }

  private void list(Context ctx) {
    ctx.header("Cache-Control", "no-store");
    ctx.json(preferences.list(RequestContexts.requireUserId(ctx)));
  }

  private void put(Context ctx) {
    long userId = RequestContexts.requireUserId(ctx);
    ctx.json(preferences.put(userId, ctx.pathParam("key"), JsonBodies.read(ctx, JsonNode.class)));
  }

  private void delete(Context ctx) {
    preferences.delete(RequestContexts.requireUserId(ctx), ctx.pathParam("key"));
    ctx.status(204);
  }
}

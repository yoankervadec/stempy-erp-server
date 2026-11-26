package com.lesconstructionssapete.stempyerpserver.modules.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.LoginRequest;
import com.lesconstructionssapete.stempyerpserver.platform.security.AuthFilter;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * POST /auth/login {"userNo","password"} -> 200 {token, expiresAt, idleTimeoutSeconds, user, companies,
 *                                                defaultCompanyId, language}
 * POST /auth/logout (Bearer token)       -> 204   (no company needed)
 * GET  /auth/me     (Bearer token)       -> 200 {user, company, permissions, language} for the request's
 *                                                company (X-Company-Id, else the default)
 * PATCH /auth/me    {"language": "fr" | "en" | null}  -> 200 as GET: the caller's own language
 *                                                (null: follow each company's)
 *
 * Login is public. Don't send an Authorization header with it: a stale token
 * is rejected (401 SESSION_INVALID) before login runs.
 */
public class AuthController {

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  public void register(Routes routes) {
    routes.post("/auth/login", this::login);
    routes.post("/auth/logout", this::logout);
    routes.get("/auth/me", this::me);
    routes.patch("/auth/me", this::updateMe);
  }

  private void login(Context ctx) {
    LoginRequest request = JsonBodies.read(ctx, LoginRequest.class);
    var response = auth.login(request, ctx.ip(), ctx.userAgent());
    ctx.header("Cache-Control", "no-store"); // the body holds a credential
    ctx.json(response);
  }

  private void logout(Context ctx) {
    RequestContexts.requireUserId(ctx); // 401 if no valid session; any company is fine
    auth.logout(AuthFilter.bearerToken(ctx).orElseThrow());
    ctx.status(204);
  }

  private void me(Context ctx) {
    ctx.json(auth.profile(RequestContexts.require(ctx)));
  }

  private void updateMe(Context ctx) {
    ctx.json(auth.updateProfile(RequestContexts.require(ctx), JsonBodies.read(ctx, JsonNode.class)));
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.gl;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * HTTP for the chart of accounts (docs/API_GUIDE.md, "General ledger"):
 *
 * <pre>
 * QUERY  /gl/accounts                  search (generic)                 gl.account.read
 * GET    /gl/accounts/{id}             one (generic)                    gl.account.read
 * GET    /gl/accounts/{id}/history     audit entries (generic)          gl.account.read + audit.history.read
 * POST   /gl/accounts                  create          201 + Location   gl.account.manage
 * PATCH  /gl/accounts/{id}             change { version, field: value } gl.account.manage
 * </pre>
 *
 * Writes answer with the row as GET shows it. Accounts are never deleted:
 * disable them ({@code isEnabled: false}).
 */
public class AccountController {

  public static final String PATH = "/gl/accounts";

  private final AccountService service;
  private final EntityEndpoints entities;

  public AccountController(AccountService service, EntityEndpoints entities) {
    this.service = service;
    this.entities = entities;
  }

  public void register(Routes routes) {
    entities.register(routes, PATH, Account.DEF, "gl.account", EntityEndpoints.Action.READ);
    // Same path and body shape as the generic writes, so the generic UI form serves them (§23.3).
    entities.declareWrite(Account.DEF, EntityEndpoints.Action.CREATE, "gl.account.manage");
    entities.declareWrite(Account.DEF, EntityEndpoints.Action.UPDATE, "gl.account.manage");
    entities.rules(Account.DEF, service);
    routes.post(PATH, ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      long id = service.createAccount(rc, JsonBodies.read(ctx, JsonNode.class));
      ctx.status(201).header("Location", PATH + "/" + id).json(row(rc, id));
    });
    routes.patch(PATH + "/{id}", ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      long id = id(ctx);
      try {
        service.updateAccount(rc, id, JsonBodies.read(ctx, JsonNode.class));
      } catch (ErpException e) {
        throw entities.withCurrent(e, rc, Account.DEF, id); // a 409 shows the account as it is now
      }
      ctx.json(row(rc, id));
    });
  }

  private Object row(RequestContext rc, long id) {
    return entities.read(rc, Account.DEF, id)
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Account " + id + " not found"));
  }

  /** A non-numeric id can't exist: 404, like a missing one. */
  private static long id(Context ctx) {
    String raw = ctx.pathParam("id");
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new ResourceNotFoundException("NOT_FOUND", "Account " + raw + " not found");
    }
  }
}

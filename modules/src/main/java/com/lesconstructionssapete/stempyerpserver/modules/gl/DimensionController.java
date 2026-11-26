package com.lesconstructionssapete.stempyerpserver.modules.gl;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * HTTP for dimensions (docs/API_GUIDE.md, "General ledger"):
 *
 * <pre>
 * QUERY / GET / history  /gl/dimensions, /gl/dimension-values      generic     gl.dimension.read
 * POST                   /gl/dimensions, /gl/dimension-values      generic     gl.dimension.create
 * PATCH                  /gl/dimensions/{id}, /gl/dimension-values/{id}       gl.dimension.update
 * GET                    /gl/accounts/{id}/dimension-rules                    gl.account.read
 * PUT                    /gl/accounts/{id}/dimension-rules  { version, rules } gl.account.manage
 * </pre>
 *
 * No DELETE: dimensions and values are disabled, never deleted.
 */
public class DimensionController {

  private final DimensionService service;
  private final EntityEndpoints entities;

  public DimensionController(DimensionService service, EntityEndpoints entities) {
    this.service = service;
    this.entities = entities;
  }

  public void register(Routes routes) {
    entities.register(routes, "/gl/dimensions", Dimension.DEF, "gl.dimension", Action.READ, Action.CREATE,
        Action.UPDATE);
    entities.register(routes, "/gl/dimension-values", DimensionValue.DEF, "gl.dimension", Action.READ,
        Action.CREATE, Action.UPDATE);
    routes.get(AccountController.PATH + "/{id}/dimension-rules", ctx -> ctx.json(service.rules(
        RequestContexts.require(ctx), id(ctx))));
    routes.put(AccountController.PATH + "/{id}/dimension-rules", ctx -> ctx.json(service.replaceRules(
        RequestContexts.require(ctx), id(ctx), JsonBodies.read(ctx, JsonNode.class))));
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

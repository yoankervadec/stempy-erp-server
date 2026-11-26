package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * HTTP for fiscal years and periods (docs/API_GUIDE.md, "General ledger"):
 *
 * <pre>
 * GET    /gl/fiscal-years                 years, oldest first, with their periods   gl.period.read
 * POST   /gl/fiscal-years                 { startDate }: a year + 12 periods  201   gl.period.manage
 * GET    /gl/periods/{id}                 one period                                gl.period.read
 * POST   /gl/periods/{id}/close           OPEN -&gt; CLOSED                            gl.period.manage
 * POST   /gl/periods/{id}/reopen          CLOSED -&gt; OPEN                            gl.period.reopen
 * GET    /gl/periods/{id}/history         closes and reopens, newest first          + audit.history.read
 * </pre>
 */
public class PeriodController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final PeriodService service;

  public PeriodController(PeriodService service) {
    this.service = service;
  }

  public void register(Routes routes) {
    routes.get("/gl/fiscal-years", ctx -> ctx.json(service.fiscalYears(RequestContexts.require(ctx))));
    routes.post("/gl/fiscal-years", ctx -> {
      var year = service.createFiscalYear(RequestContexts.require(ctx), JsonBodies.read(ctx, JsonNode.class));
      ctx.status(201).header("Location", "/gl/fiscal-years").json(year);
    });
    routes.get("/gl/periods/{id}", ctx -> ctx.json(service.getPeriod(RequestContexts.require(ctx), id(ctx))));
    routes.post("/gl/periods/{id}/close", ctx -> ctx.json(service.closePeriod(RequestContexts.require(ctx),
        id(ctx))));
    routes.post("/gl/periods/{id}/reopen", ctx -> ctx.json(service.reopenPeriod(RequestContexts.require(ctx),
        id(ctx))));
    routes.get("/gl/periods/{id}/history", ctx -> {
      int limit = intParam(ctx, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
      int offset = intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE);
      ctx.json(Map.of("data", service.periodHistory(RequestContexts.require(ctx), id(ctx), limit, offset),
          "page", Map.of("limit", limit, "offset", offset)));
    });
  }

  /** A non-numeric id can't exist: 404, like a missing one. */
  private static long id(Context ctx) {
    String raw = ctx.pathParam("id");
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new ResourceNotFoundException("NOT_FOUND", "Period " + raw + " not found");
    }
  }

  private static int intParam(Context ctx, String name, int fallback, int min, int max) {
    String raw = ctx.queryParam(name);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      int value = Integer.parseInt(raw.strip());
      if (value >= min && value <= max) {
        return value;
      }
    } catch (NumberFormatException e) {
      // reported below
    }
    throw new BadRequestException("INVALID_PARAMETER", name + " must be a whole number from " + min + " to " + max);
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.ResourceAction;
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
 *
 * Generic (docs/ARCHITECTURE.md §23): QUERY /gl/fiscal-years and /gl/periods,
 * GET /gl/fiscal-years/new and /gl/fiscal-years/{id}; the Close and Reopen
 * actions; _refs and _meta on GET /gl/periods/{id}.
 * </pre>
 */
public class PeriodController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
  };

  static final ResourceAction CLOSE = ResourceAction.onRecord("close").post("/close")
      .permission(PeriodService.MANAGE).confirm()
      .when(row -> PeriodStatus.OPEN.name().equals(String.valueOf(row.get("status"))));
  static final ResourceAction REOPEN = ResourceAction.onRecord("reopen").post("/reopen")
      .permission(PeriodService.REOPEN).confirm()
      .when(row -> PeriodStatus.CLOSED.name().equals(String.valueOf(row.get("status"))));

  private final PeriodService service;
  private final EntityEndpoints entities;

  public PeriodController(PeriodService service, EntityEndpoints entities) {
    this.service = service;
    this.entities = entities;
  }

  public void register(Routes routes) {
    entities.registerQuery(routes, "/gl/fiscal-years", FiscalYear.DEF, "gl.period");
    // a new year's form is its start date: the generic create form posts to the hand-written POST below
    entities.declareWrite(FiscalYear.DEF, EntityEndpoints.Action.CREATE, PeriodService.MANAGE);
    routes.get("/gl/fiscal-years/new", entities.newRecord(FiscalYear.DEF));
    routes.get("/gl/fiscal-years/{id}", entities.get(FiscalYear.DEF, PeriodService.READ));
    entities.registerQuery(routes, "/gl/periods", Period.DEF, "gl.period");
    entities.action(Period.DEF, CLOSE);
    entities.action(Period.DEF, REOPEN);

    routes.get("/gl/fiscal-years", ctx -> ctx.json(service.fiscalYears(RequestContexts.require(ctx))));
    routes.post("/gl/fiscal-years", ctx -> {
      var year = service.createFiscalYear(RequestContexts.require(ctx), JsonBodies.read(ctx, JsonNode.class));
      ctx.status(201).header("Location", "/gl/fiscal-years").json(year);
    });
    routes.get("/gl/periods/{id}", ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      ctx.json(decorated(rc, service.getPeriod(rc, id(ctx))));
    });
    routes.post("/gl/periods/{id}/close", ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      ctx.json(decorated(rc, service.closePeriod(rc, id(ctx))));
    });
    routes.post("/gl/periods/{id}/reopen", ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      ctx.json(decorated(rc, service.reopenPeriod(rc, id(ctx))));
    });
    routes.get("/gl/periods/{id}/history", ctx -> {
      int limit = intParam(ctx, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
      int offset = intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE);
      ctx.json(Map.of("data", service.periodHistory(RequestContexts.require(ctx), id(ctx), limit, offset),
          "page", Map.of("limit", limit, "offset", offset)));
    });
  }

  /** A period as every answer shows it: the view with _refs and _meta (its Close / Reopen action). */
  private Map<String, Object> decorated(RequestContext rc, Object view) {
    return entities.decorate(rc, Period.DEF, JSON.convertValue(view, MAP), Map.of());
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

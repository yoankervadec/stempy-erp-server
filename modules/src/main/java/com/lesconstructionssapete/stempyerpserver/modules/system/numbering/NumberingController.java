package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberingService.Page;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * HTTP for the numbering admin API (docs/API_GUIDE.md, "Document numbering"):
 *
 * <pre>
 * GET    /numbering/document-types                          what can be numbered, and with which parameters
 * GET    /numbering/series?documentType=&amp;limit=&amp;offset=     the company's series
 * POST   /numbering/series                                  create                     201 + Location
 * GET    /numbering/series/{id}                             one
 * PATCH  /numbering/series/{id}                             change { version, field: value }
 * GET    /numbering/series/{id}/counters                    counters and the next number of each
 * POST   /numbering/series/{id}/counters/set-next           { params, nextNumber }: move a counter up
 * GET    /numbering/series/{id}/history                     audit entries, newest first
 * </pre>
 *
 * Series are never deleted: disable them ({@code isEnabled: false}).
 */
public class NumberingController {

  private final NumberingService service;

  public NumberingController(NumberingService service) {
    this.service = service;
  }

  public void register(Routes routes) {
    routes.get("/numbering/document-types", ctx -> ctx.json(service.documentTypes(RequestContexts.require(ctx))));

    routes.get("/numbering/series", ctx -> ctx.json(page(service.listSeries(RequestContexts.require(ctx),
        blankToNull(ctx.queryParam("documentType")), page(ctx)))));
    routes.post("/numbering/series", ctx -> {
      var created = service.createSeries(RequestContexts.require(ctx), JsonBodies.read(ctx, JsonNode.class));
      ctx.status(201).header("Location", "/numbering/series/" + created.id()).json(created);
    });
    routes.get("/numbering/series/{id}", ctx -> ctx.json(service.getSeries(RequestContexts.require(ctx), id(ctx))));
    routes.patch("/numbering/series/{id}", ctx -> ctx.json(service.updateSeries(RequestContexts.require(ctx),
        id(ctx), JsonBodies.read(ctx, JsonNode.class))));

    routes.get("/numbering/series/{id}/counters", ctx -> ctx.json(service.counters(RequestContexts.require(ctx),
        id(ctx))));
    routes.post("/numbering/series/{id}/counters/set-next", ctx -> ctx.json(service.setNextNumber(
        RequestContexts.require(ctx), id(ctx), JsonBodies.read(ctx, JsonNode.class))));

    routes.get("/numbering/series/{id}/history", ctx -> ctx.json(page(service.seriesHistory(
        RequestContexts.require(ctx), id(ctx), page(ctx)))));
  }

  // ---- request parsing ----

  /** A non-numeric id can't exist: 404, like a missing one. */
  private static long id(Context ctx) {
    String raw = ctx.pathParam("id");
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new ResourceNotFoundException("NOT_FOUND", "Number series " + raw + " not found");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static Page page(Context ctx) {
    return new Page(intParam(ctx, "limit", NumberingService.DEFAULT_LIMIT, 1, NumberingService.MAX_LIMIT),
        intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE));
  }

  private static Map<String, Object> page(NumberingService.Listing<?> listing) {
    return Map.of("data", listing.data(),
        "page", Map.of("limit", listing.page().limit(), "offset", listing.page().offset()));
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

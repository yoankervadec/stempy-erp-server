package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.SchedulingService.Page;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * HTTP for the scheduler admin API (docs/API_GUIDE.md, "Scheduler"):
 *
 * <pre>
 * GET    /scheduler/job-types                      what can be scheduled
 * GET    /scheduler/schedules?scope=&amp;limit=&amp;offset=  list (scope COMPANY | SYSTEM, default both visible)
 * POST   /scheduler/schedules                      create                          201 + Location
 * GET    /scheduler/schedules/{id}                 one
 * PATCH  /scheduler/schedules/{id}                 change { version, field: value }
 * DELETE /scheduler/schedules/{id}?version=N       soft delete                     204
 * GET    /scheduler/schedules/{id}/preview?count=  next fire times
 * POST   /scheduler/preview                        next fire times of an unsaved schedule
 * POST   /scheduler/schedules/{id}/runs            run now, as the caller           202 + Location
 * GET    /scheduler/runs?scheduleId=&amp;status=&amp;limit=&amp;offset=   history, newest first
 * GET    /scheduler/runs/{id}                      one run (poll it after "run now")
 * </pre>
 */
public class SchedulingController {

  private final SchedulingService service;

  public SchedulingController(SchedulingService service) {
    this.service = service;
  }

  public void register(Routes routes) {
    routes.get("/scheduler/job-types", ctx -> ctx.json(service.jobTypes(RequestContexts.require(ctx))));

    routes.get("/scheduler/schedules", ctx -> ctx.json(page(service.listSchedules(RequestContexts.require(ctx),
        scope(ctx), page(ctx)))));
    routes.post("/scheduler/schedules", ctx -> {
      var created = service.createSchedule(RequestContexts.require(ctx), JsonBodies.read(ctx, JsonNode.class));
      ctx.status(201).header("Location", "/scheduler/schedules/" + created.id()).json(created);
    });
    routes.get("/scheduler/schedules/{id}", ctx -> ctx.json(service.getSchedule(RequestContexts.require(ctx),
        id(ctx, "Schedule"))));
    routes.patch("/scheduler/schedules/{id}", ctx -> ctx.json(service.updateSchedule(RequestContexts.require(ctx),
        id(ctx, "Schedule"), JsonBodies.read(ctx, JsonNode.class))));
    routes.delete("/scheduler/schedules/{id}", ctx -> {
      service.deleteSchedule(RequestContexts.require(ctx), id(ctx, "Schedule"),
          intParam(ctx, "version", null, 0, Integer.MAX_VALUE));
      ctx.status(204);
    });

    routes.get("/scheduler/schedules/{id}/preview", ctx -> ctx.json(service.previewSchedule(
        RequestContexts.require(ctx), id(ctx, "Schedule"), intParam(ctx, "count", 10, 1, 50))));
    routes.post("/scheduler/preview", ctx -> ctx.json(service.previewDraft(RequestContexts.require(ctx),
        JsonBodies.read(ctx, JsonNode.class))));

    routes.post("/scheduler/schedules/{id}/runs", ctx -> {
      JsonNode body = ctx.body().isBlank() ? null : JsonBodies.read(ctx, JsonNode.class);
      var run = service.requestRun(RequestContexts.require(ctx), id(ctx, "Schedule"), body);
      ctx.status(202).header("Location", "/scheduler/runs/" + run.id()).json(run);
    });
    routes.get("/scheduler/runs", ctx -> ctx.json(page(service.listRuns(RequestContexts.require(ctx),
        longParam(ctx, "scheduleId"), status(ctx), page(ctx)))));
    routes.get("/scheduler/runs/{id}", ctx -> ctx.json(service.getRun(RequestContexts.require(ctx),
        id(ctx, "Run"))));
  }

  // ---- request parsing ----

  /** A non-numeric id can't exist: 404, like a missing one. */
  private static long id(Context ctx, String resource) {
    String raw = ctx.pathParam("id");
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new ResourceNotFoundException("NOT_FOUND", resource + " " + raw + " not found");
    }
  }

  private static Page page(Context ctx) {
    return new Page(intParam(ctx, "limit", SchedulingService.DEFAULT_LIMIT, 1, SchedulingService.MAX_LIMIT),
        intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE));
  }

  private static Map<String, Object> page(SchedulingService.Listing<?> listing) {
    return Map.of("data", listing.data(),
        "page", Map.of("limit", listing.page().limit(), "offset", listing.page().offset()));
  }

  private static ScheduleScope scope(Context ctx) {
    String raw = ctx.queryParam("scope");
    return raw == null || raw.isBlank() ? null : enumParam("scope", raw, ScheduleScope.class);
  }

  private static RunStatus status(Context ctx) {
    String raw = ctx.queryParam("status");
    return raw == null || raw.isBlank() ? null : enumParam("status", raw, RunStatus.class);
  }

  private static Integer intParam(Context ctx, String name, Integer fallback, int min, int max) {
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

  private static Long longParam(Context ctx, String name) {
    String raw = ctx.queryParam(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new BadRequestException("INVALID_PARAMETER", name + " must be a number");
    }
  }

  private static <E extends Enum<E>> E enumParam(String name, String raw, Class<E> type) {
    try {
      return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("INVALID_PARAMETER", name + " must be one of "
          + Arrays.toString(type.getEnumConstants()));
    }
  }
}

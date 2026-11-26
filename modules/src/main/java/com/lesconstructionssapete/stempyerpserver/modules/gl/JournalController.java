package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.idempotency.IdempotencyKey;
import com.lesconstructionssapete.stempyerpserver.platform.web.ActionParam;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonBodies;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordLayout;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.ResourceAction;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * HTTP for journal entries (docs/API_GUIDE.md, "General ledger"):
 *
 * <pre>
 * POST   /gl/journal-entries                     a manual entry  201 + Location   gl.journal.post
 *        (Idempotency-Key header: a retry answers with the first entry, Idempotent-Replayed: true)
 * GET    /gl/journal-entries?from=&amp;to=&amp;sourceType=&amp;limit=&amp;offset=   newest first      gl.journal.read
 * GET    /gl/journal-entries/{id}                one, with its lines                gl.journal.read
 * POST   /gl/journal-entries/{id}/reverse        { postingDate, description? }  201 gl.journal.reverse
 * GET    /gl/journal-entries/{id}/history        POSTED / REVERSED entries          + audit.history.read
 * </pre>
 */
public class JournalController {

  public static final String PATH = "/gl/journal-entries";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
  };

  /** Reverse an entry not yet reversed and not itself a reversal (§22.5). */
  static final ResourceAction REVERSE = ResourceAction.onRecord("reverse").post("/reverse")
      .permission(JournalService.REVERSE)
      .param(ActionParam.date("postingDate").required())
      .param(ActionParam.string("description").maxLength(255))
      .confirm()
      .when(row -> row.get("reversedById") == null && row.get("reversalOfId") == null);

  private final JournalService service;
  private final EntityEndpoints entities;

  public JournalController(JournalService service, EntityEndpoints entities) {
    this.service = service;
    this.entities = entities;
  }

  public void register(Routes routes) {
    // the generic list, the Reverse button and the entry page (docs/ARCHITECTURE.md §23.12, GL pilot)
    entities.registerQuery(routes, PATH, JournalEntry.DEF, "gl.journal");
    entities.action(JournalEntry.DEF, REVERSE);
    entities.layout(JournalEntry.DEF, RecordLayout.of(JournalEntry.DEF)
        .section("general", 2, JournalEntry.ENTRY_NO, JournalEntry.POSTING_DATE, JournalEntry.PERIOD_ID,
            JournalEntry.TOTAL, JournalEntry.DESCRIPTION)
        .section("source", 2, JournalEntry.SOURCE_TYPE, JournalEntry.SOURCE_NO, JournalEntry.REVERSAL_OF_ID,
            JournalEntry.REVERSED_BY_ID)
        .collection(JournalEntry.LINES_REL, c -> c
            .columns(JournalLine.LINE_NO, JournalLine.ACCOUNT_ID, JournalLine.DESCRIPTION, JournalLine.DEBIT,
                JournalLine.CREDIT)
            .footerSum(JournalLine.DEBIT, JournalLine.CREDIT))
        .tab(RecordLayout.HISTORY));
    routes.post(PATH, ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      var posted = service.postManual(rc, JsonBodies.read(ctx, JsonNode.class), ctx.header(IdempotencyKey.HEADER));
      ctx.status(201).header("Location", PATH + "/" + posted.entry().id());
      if (posted.replayed()) {
        ctx.header(REPLAYED_HEADER, "true");
      }
      ctx.json(decorated(rc, posted.entry()));
    });
    routes.get(PATH, ctx -> {
      int limit = intParam(ctx, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
      int offset = intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE);
      String sourceType = ctx.queryParam("sourceType");
      ctx.json(Map.of("data", service.listEntries(RequestContexts.require(ctx), dateParam(ctx, "from"),
          dateParam(ctx, "to"), sourceType == null || sourceType.isBlank() ? null : sourceType.strip(), limit, offset),
          "page", Map.of("limit", limit, "offset", offset)));
    });
    routes.get(PATH + "/{id}", ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      ctx.json(decorated(rc, service.getEntry(rc, id(ctx))));
    });
    routes.post(PATH + "/{id}/reverse", ctx -> {
      RequestContext rc = RequestContexts.require(ctx);
      var reversal = service.reverse(rc, id(ctx), JsonBodies.read(ctx, JsonNode.class));
      ctx.status(201).header("Location", PATH + "/" + reversal.id()).json(decorated(rc, reversal));
    });
    routes.get(PATH + "/{id}/history", ctx -> {
      int limit = intParam(ctx, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
      int offset = intParam(ctx, "offset", 0, 0, Integer.MAX_VALUE);
      ctx.json(Map.of("data", service.history(RequestContexts.require(ctx), id(ctx), limit, offset),
          "page", Map.of("limit", limit, "offset", offset)));
    });
  }

  /** An entry as every answer shows it: the view with _refs (entry and lines) and _meta (§23.9). */
  private Map<String, Object> decorated(RequestContext rc, Object view) {
    Map<String, Object> entry = JSON.convertValue(view, MAP);
    return entities.decorate(rc, JournalEntry.DEF, entry, Map.of("lines", JournalLine.DEF));
  }

  /** A non-numeric id can't exist: 404, like a missing one. */
  private static long id(Context ctx) {
    String raw = ctx.pathParam("id");
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new ResourceNotFoundException("NOT_FOUND", "Journal entry " + raw + " not found");
    }
  }

  private static LocalDate dateParam(Context ctx, String name) {
    String raw = ctx.queryParam(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.strip());
    } catch (DateTimeParseException e) {
      throw new BadRequestException("INVALID_PARAMETER", name + " must be a date such as 2026-04-30");
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

package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportService.DimensionFilter;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

import io.javalin.http.Context;

/**
 * HTTP for GL reports (docs/API_GUIDE.md, "General ledger"), gl.report.read:
 *
 * <pre>
 * GET /gl/reports/trial-balance?from=&amp;to=[&amp;dimension=&amp;value=][&amp;includeZero=true]
 * GET /gl/reports/account-ledger?accountNo=&amp;from=&amp;to=[&amp;dimension=&amp;value=]
 * </pre>
 */
public class ReportController {

  private final ReportService service;

  public ReportController(ReportService service) {
    this.service = service;
  }

  public void register(Routes routes) {
    routes.get("/gl/reports/trial-balance", ctx -> ctx.json(service.trialBalance(RequestContexts.require(ctx),
        date(ctx, "from"), date(ctx, "to"), filter(ctx), bool(ctx, "includeZero"))));
    routes.get("/gl/reports/account-ledger", ctx -> ctx.json(service.accountLedger(RequestContexts.require(ctx),
        text(ctx, "accountNo"), date(ctx, "from"), date(ctx, "to"), filter(ctx))));
  }

  private static DimensionFilter filter(Context ctx) {
    String dimension = text(ctx, "dimension");
    String value = text(ctx, "value");
    return dimension == null && value == null ? ReportService.noFilter() : new DimensionFilter(dimension, value);
  }

  private static String text(Context ctx, String name) {
    String raw = ctx.queryParam(name);
    return raw == null || raw.isBlank() ? null : raw.strip();
  }

  /** Null when absent: the service reports it as required. */
  private static LocalDate date(Context ctx, String name) {
    String raw = text(ctx, name);
    if (raw == null) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new BadRequestException("INVALID_PARAMETER", name + " must be a date such as 2026-04-30");
    }
  }

  private static boolean bool(Context ctx, String name) {
    String raw = text(ctx, name);
    if (raw == null || raw.equalsIgnoreCase("false")) {
      return false;
    }
    if (raw.equalsIgnoreCase("true")) {
      return true;
    }
    throw new BadRequestException("INVALID_PARAMETER", name + " must be true or false");
  }
}

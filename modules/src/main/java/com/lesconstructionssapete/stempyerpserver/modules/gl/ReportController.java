package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.lesconstructionssapete.stempyerpserver.modules.gl.ReportService.DimensionFilter;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Format;
import com.lesconstructionssapete.stempyerpserver.platform.error.BadRequestException;
import com.lesconstructionssapete.stempyerpserver.platform.web.ActionParam;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.Report;
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

  /** The trial balance (§22.6), for the generic report page (docs/ARCHITECTURE.md §23.11). */
  static final Report TRIAL_BALANCE = Report.of("glTrialBalance", "/gl/reports/trial-balance")
      .permission(ReportService.READ)
      .param(ActionParam.date("from").required())
      .param(ActionParam.date("to").required())
      .param(ActionParam.bool("includeZero"))
      .param(ActionParam.string("dimension"))
      .param(ActionParam.string("value"))
      .rows("rows").level("level").emphasis("isHeading")
      .column("accountNo", FieldType.STRING)
      .column("name", FieldType.STRING)
      .column("accountType", FieldType.enumOf(AccountType.class))
      .column("amounts.opening", FieldType.DECIMAL, Format.MONEY).summedAs("opening")
      .column("amounts.debits", FieldType.DECIMAL, Format.MONEY).summedAs("debits")
      .column("amounts.credits", FieldType.DECIMAL, Format.MONEY).summedAs("credits")
      .column("amounts.closing", FieldType.DECIMAL, Format.MONEY).summedAs("closing")
      .totals("totals");

  /** One account's lines with a running balance (§22.6). */
  static final Report ACCOUNT_LEDGER = Report.of("glAccountLedger", "/gl/reports/account-ledger")
      .permission(ReportService.READ)
      .param(ActionParam.string("accountNo").required())
      .param(ActionParam.date("from").required())
      .param(ActionParam.date("to").required())
      .param(ActionParam.string("dimension"))
      .param(ActionParam.string("value"))
      .figure("opening", FieldType.DECIMAL, Format.MONEY)
      .figure("debits", FieldType.DECIMAL, Format.MONEY)
      .figure("credits", FieldType.DECIMAL, Format.MONEY)
      .figure("closing", FieldType.DECIMAL, Format.MONEY)
      .rows("lines")
      .column("postingDate", FieldType.DATE)
      .column("entryNo", FieldType.STRING)
      .column("description", FieldType.STRING)
      .column("debit", FieldType.DECIMAL, Format.MONEY)
      .column("credit", FieldType.DECIMAL, Format.MONEY)
      .column("balance", FieldType.DECIMAL, Format.MONEY);

  private final EntityEndpoints entities;

  public ReportController(ReportService service, EntityEndpoints entities) {
    this.service = service;
    this.entities = entities;
  }

  public void register(Routes routes) {
    entities.report(TRIAL_BALANCE);
    entities.report(ACCOUNT_LEDGER);
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

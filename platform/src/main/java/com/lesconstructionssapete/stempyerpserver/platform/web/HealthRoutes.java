package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Map;

/**
 * GET /health/live : the process is up. Never touches dependencies; for
 * "restart me if this fails".
 * GET /health/ready : dependencies work (200, or 503 if any check fails); for
 * load balancers and Docker/Kubernetes health checks that decide whether to
 * send traffic.
 *
 */

// TODO Both are unauthenticated. When AuthFilter exists, make sure it lets
// /health/ through.
public final class HealthRoutes {

  private HealthRoutes() {
  }

  public static void register(Routes routes, HealthChecks checks) {
    routes.get("/health/live", ctx -> ctx.json(Map.of("status", "UP")));

    routes.get("/health/ready", ctx -> {
      HealthChecks.Report report = checks.run();
      ctx.status(report.up() ? 200 : 503);
      ctx.json(Map.of("status", report.up() ? "UP" : "DOWN", "checks", report.checks()));
    });
  }
}

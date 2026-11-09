package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named readiness checks, e.g. "database" and later "redis". A check is a
 * Runnable that returns normally when healthy and throws when not. Failure
 * details are logged, never returned: the health endpoints are unauthenticated.
 */
public final class HealthChecks {

  private static final Logger log = LoggerFactory.getLogger(HealthChecks.class);

  public record Report(boolean up, Map<String, String> checks) {
  }

  private final Map<String, Runnable> checks = new LinkedHashMap<>();

  public HealthChecks add(String name, Runnable check) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(check, "check");
    if (checks.putIfAbsent(name, check) != null) {
      throw new IllegalStateException("Health check already registered: " + name);
    }
    return this;
  }

  public Report run() {
    boolean allUp = true;
    Map<String, String> results = new LinkedHashMap<>();
    for (var entry : checks.entrySet()) {
      try {
        entry.getValue().run();
        results.put(entry.getKey(), "UP");
      } catch (Exception e) {
        allUp = false;
        results.put(entry.getKey(), "DOWN");
        log.warn("Health check '{}' failed", entry.getKey(), e);
      }
    }
    return new Report(allUp, java.util.Collections.unmodifiableMap(results));
  }
}

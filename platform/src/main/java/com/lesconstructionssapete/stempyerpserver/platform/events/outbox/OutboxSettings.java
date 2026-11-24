package com.lesconstructionssapete.stempyerpserver.platform.events.outbox;

import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.SchedulerSettings;

/**
 * The outbox.* settings.
 *
 * @param enabled            false: this instance delivers nothing (rows are
 *                           still written, and other instances deliver them)
 * @param pollInterval       how often pending rows are looked for when idle; a
 *                           worker finishing a full batch looks again at once
 * @param workerThreads      handlers run at most this many at a time on this
 *                           instance
 * @param defaultTimeout     for subscriptions that set none
 * @param defaultMaxAttempts for subscriptions that set none
 * @param retryBackoff       a failed attempt n is retried after
 *                           retryBackoff * 2^(n-1), capped at 1 hour
 * @param shutdownGrace      how long stop() waits for running handlers before
 *                           interrupting them
 * @param retention          SUCCEEDED rows older than this are pruned
 * @param deadRetention      DEAD rows older than this are pruned
 * @param instanceId         names this instance in leases (locked_by)
 */
public record OutboxSettings(boolean enabled, Duration pollInterval, int workerThreads, Duration defaultTimeout,
    int defaultMaxAttempts, Duration retryBackoff, Duration shutdownGrace, Duration retention,
    Duration deadRetention, String instanceId) {

  public OutboxSettings {
    Objects.requireNonNull(instanceId, "instanceId");
    require(pollInterval.compareTo(Duration.ofMillis(50)) >= 0, "outbox.pollInterval must be at least 50 ms");
    require(workerThreads >= 1 && workerThreads <= 64, "outbox.workerThreads must be 1-64");
    require(defaultTimeout.compareTo(Duration.ofMillis(100)) >= 0
        && defaultTimeout.compareTo(Duration.ofHours(1)) <= 0, "outbox.defaultTimeout must be between PT0.1S and PT1H");
    require(defaultMaxAttempts >= 1 && defaultMaxAttempts <= 100, "outbox.defaultMaxAttempts must be 1-100");
    require(!retryBackoff.isNegative(), "outbox.retryBackoff must not be negative");
    require(!shutdownGrace.isNegative(), "outbox.shutdownGrace must not be negative");
    require(retention.compareTo(Duration.ofDays(1)) >= 0, "outbox.retention must be at least P1D");
    require(deadRetention.compareTo(Duration.ofDays(1)) >= 0, "outbox.deadRetention must be at least P1D");
    require(!instanceId.isBlank() && instanceId.length() <= 100, "instanceId must be 1-100 characters");
  }

  public static OutboxSettings from(Settings s) {
    return new OutboxSettings(
        Boolean.parseBoolean(s.get("outbox.enabled", "true")),
        s.getDuration("outbox.pollInterval", Duration.ofSeconds(1)),
        s.getInt("outbox.workerThreads", 4),
        s.getDuration("outbox.defaultTimeout", Duration.ofSeconds(30)),
        s.getInt("outbox.defaultMaxAttempts", 8),
        s.getDuration("outbox.retryBackoff", Duration.ofSeconds(10)),
        s.getDuration("outbox.shutdownGrace", Duration.ofSeconds(15)),
        s.getDuration("outbox.retention", Duration.ofDays(7)),
        s.getDuration("outbox.deadRetention", Duration.ofDays(90)),
        s.get("outbox.instanceId", SchedulerSettings.defaultInstanceId()));
  }

  public static OutboxSettings defaults() {
    return from(Settings.empty());
  }

  public OutboxSettings withInstanceId(String id) {
    return new OutboxSettings(enabled, pollInterval, workerThreads, defaultTimeout, defaultMaxAttempts, retryBackoff,
        shutdownGrace, retention, deadRetention, id);
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}

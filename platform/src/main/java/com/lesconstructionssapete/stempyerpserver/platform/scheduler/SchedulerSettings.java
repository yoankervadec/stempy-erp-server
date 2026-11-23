package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;

/**
 * The scheduler's scheduler.* settings.
 *
 * @param enabled        false: this instance serves HTTP only and runs no jobs
 * @param pollInterval   how often due schedules are looked for; an edit takes
 *                       effect within this delay
 * @param workerThreads  jobs run at most this many at a time on this instance
 * @param lease          how long a claimed schedule stays locked without a
 *                       renewal; renewed every lease/3 while the job runs, so
 *                       a crashed instance's runs are taken over after this
 * @param defaultTimeout for job types and schedules that set none
 * @param shutdownGrace  how long stop() waits for running jobs before
 *                       interrupting them
 * @param runRetention   finished runs older than this are pruned
 * @param instanceId     names this instance in locks and run rows
 */
public record SchedulerSettings(boolean enabled, Duration pollInterval, int workerThreads, Duration lease,
    Duration defaultTimeout, Duration shutdownGrace, Duration runRetention, String instanceId) {

  public SchedulerSettings {
    Objects.requireNonNull(instanceId, "instanceId");
    require(pollInterval.compareTo(Duration.ofMillis(50)) >= 0, "scheduler.pollInterval must be at least 50 ms");
    require(workerThreads >= 1 && workerThreads <= 64, "scheduler.workerThreads must be 1-64");
    require(lease.compareTo(Duration.ofSeconds(3)) >= 0, "scheduler.lease must be at least PT3S");
    require(defaultTimeout.isPositive(), "scheduler.defaultTimeout must be positive");
    require(!shutdownGrace.isNegative(), "scheduler.shutdownGrace must not be negative");
    require(runRetention.compareTo(Duration.ofDays(1)) >= 0, "scheduler.runRetention must be at least P1D");
    require(!instanceId.isBlank() && instanceId.length() <= 100, "instanceId must be 1-100 characters");
  }

  public static SchedulerSettings from(Settings s) {
    return new SchedulerSettings(
        Boolean.parseBoolean(s.get("scheduler.enabled", "true")),
        s.getDuration("scheduler.pollInterval", Duration.ofSeconds(5)),
        s.getInt("scheduler.workerThreads", 4),
        s.getDuration("scheduler.lease", Duration.ofMinutes(1)),
        s.getDuration("scheduler.defaultTimeout", Duration.ofHours(1)),
        s.getDuration("scheduler.shutdownGrace", Duration.ofSeconds(30)),
        s.getDuration("scheduler.runRetention", Duration.ofDays(90)),
        s.get("scheduler.instanceId", defaultInstanceId()));
  }

  public static SchedulerSettings defaults() {
    return from(Settings.empty());
  }

  public SchedulerSettings withInstanceId(String id) {
    return new SchedulerSettings(enabled, pollInterval, workerThreads, lease, defaultTimeout, shutdownGrace,
        runRetention, id);
  }

  /** host:pid:random, unique even for two instances on one host started in the same second. */
  public static String defaultInstanceId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "unknown-host";
    }
    if (host.length() > 60) {
      host = host.substring(0, 60);
    }
    String random = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 6);
    return host + ":" + ManagementFactory.getRuntimeMXBean().getPid() + ":" + random;
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}

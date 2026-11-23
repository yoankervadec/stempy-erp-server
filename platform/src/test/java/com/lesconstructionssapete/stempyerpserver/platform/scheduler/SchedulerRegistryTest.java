package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/** Job types, the registry's lifecycle and settings: everything that needs no database. */
class SchedulerRegistryTest {

  private static final Job NOTHING = ctx -> {
  };

  @Test
  void registersAndFreezes() {
    var scheduler = new Scheduler();
    scheduler.register(JobType.of("users.purge-sessions", "Purge", NOTHING));
    assertThrows(IllegalStateException.class, scheduler::jobTypes); // not frozen yet
    scheduler.freeze();
    assertTrue(scheduler.jobType("users.purge-sessions").isPresent());
    assertTrue(scheduler.jobType("nope").isEmpty());
    assertThrows(IllegalStateException.class, () -> scheduler.register(JobType.of("late.job", "Late", NOTHING)));
    assertFalse(scheduler.isRunning());
    scheduler.stop(); // never started: no-op
  }

  @Test
  void rejectsDuplicateKeys() {
    var scheduler = new Scheduler();
    scheduler.register(JobType.of("a.job", "A", NOTHING));
    assertThrows(IllegalStateException.class, () -> scheduler.register(JobType.of("a.job", "Again", NOTHING)));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "Upper.case", "spaces in it", "trailing.", ".leading", "double..dot", "under_score" })
  void rejectsBadKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> JobType.of(key, "Bad", NOTHING));
  }

  @Test
  void jobTypeOptions() {
    var type = JobType.of("gl.close-period", "Close", NOTHING)
        .withTimeout(Duration.ofMinutes(5))
        .withDefaultSchedule(DefaultSchedule.cron("Nightly close", "0 2 * * *", "America/Toronto"));
    assertEquals(Duration.ofMinutes(5), type.timeout());
    assertEquals("Nightly close", type.defaultSchedule().name());
    assertThrows(IllegalArgumentException.class, () -> type.withTimeout(Duration.ZERO));
  }

  @Test
  void badDefaultSchedulesFailAtStartup() {
    assertThrows(ValidationFailedException.class, () -> DefaultSchedule.cron("Bad", "0 25 * * *", "UTC"));
    assertThrows(ValidationFailedException.class, () -> DefaultSchedule.interval("Too often", "PT10S"));
  }

  @Test
  void settingsDefaultsAndOverrides() {
    var defaults = SchedulerSettings.defaults();
    assertTrue(defaults.enabled());
    assertEquals(Duration.ofSeconds(5), defaults.pollInterval());
    assertEquals(4, defaults.workerThreads());

    var custom = SchedulerSettings.from(Settings.of(Map.of(
        "scheduler.enabled", "false", "scheduler.workerThreads", "2", "scheduler.instanceId", "node-a")));
    assertFalse(custom.enabled());
    assertEquals(2, custom.workerThreads());
    assertEquals("node-a", custom.instanceId());

    assertThrows(IllegalArgumentException.class,
        () -> SchedulerSettings.from(Settings.of(Map.of("scheduler.workerThreads", "0"))));
    assertThrows(IllegalArgumentException.class,
        () -> SchedulerSettings.from(Settings.of(Map.of("scheduler.lease", "PT1S"))));
  }

  @Test
  void defaultInstanceIdsDiffer() {
    assertFalse(SchedulerSettings.defaultInstanceId().equals(SchedulerSettings.defaultInstanceId()));
  }
}

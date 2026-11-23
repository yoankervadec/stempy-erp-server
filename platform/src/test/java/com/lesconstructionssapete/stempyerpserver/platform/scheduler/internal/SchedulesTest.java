package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

class SchedulesTest {

  private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-23T10:07:00Z");

  private static ScheduleRow row(ScheduleType type, String cron, String interval, boolean enabled, Instant startAt,
      Instant endAt) {
    return new ScheduleRow(1, null, "test.job", "Test", type, cron, interval, "UTC", null, enabled, startAt, endAt,
        MisfirePolicy.FIRE_ONCE, 300, null, 0, 60, null, null, 0, null, null, null, null, CREATED);
  }

  private static ScheduleRow cron(String cron) {
    return row(ScheduleType.CRON, cron, null, true, null, null);
  }

  @Test
  void fingerprintFollowsTheTimingColumnsOnly() {
    String a = Schedules.fingerprint(cron("*/15 * * * *"));
    assertNotNull(a);
    assertEquals(64, a.length());
    assertEquals(a, Schedules.fingerprint(cron("*/15 * * * *")));
    assertNotEquals(a, Schedules.fingerprint(cron("*/5 * * * *")));
    assertNotEquals(a, Schedules.fingerprint(row(ScheduleType.CRON, "*/15 * * * *", null, true, NOW, null)));
  }

  @Test
  void disabledRowsHaveNoFingerprint() {
    assertNull(Schedules.fingerprint(row(ScheduleType.CRON, "*/15 * * * *", null, false, null, null)));
  }

  @Test
  void invalidRowsStillHaveAFingerprint() {
    ScheduleRow bad = cron("not a cron");
    assertNotNull(Schedules.fingerprint(bad));
    assertThrows(ValidationFailedException.class, () -> Schedules.spec(bad));
  }

  @Test
  void intervalCountsFromCreatedAtOrStartAt() {
    ScheduleRow fromCreated = row(ScheduleType.INTERVAL, null, "PT25M", true, null, null);
    // 2026-11-01T00:00 + k * 25 min: 10:07 falls between 09:55 and 10:20
    assertEquals(Optional.of(Instant.parse("2026-11-23T10:20:00Z")),
        Schedules.nextFire(Schedules.spec(fromCreated), fromCreated, NOW));

    ScheduleRow fromStart = row(ScheduleType.INTERVAL, null, "PT25M", true, Instant.parse("2026-11-23T10:00:00Z"), null);
    assertEquals(Optional.of(Instant.parse("2026-11-23T10:25:00Z")),
        Schedules.nextFire(Schedules.spec(fromStart), fromStart, NOW));
  }

  @Test
  void nothingFiresBeforeStartAtAndAFireAtStartAtCounts() {
    Instant start = Instant.parse("2026-12-01T09:00:00Z");
    ScheduleRow r = row(ScheduleType.CRON, "0 9 * * *", null, true, start, null);
    assertEquals(Optional.of(start), Schedules.nextFire(Schedules.spec(r), r, NOW));
  }

  @Test
  void nothingFiresAfterEndAt() {
    ScheduleRow r = row(ScheduleType.CRON, "0 9 * * *", null, true, null, Instant.parse("2026-11-24T09:00:00Z"));
    assertEquals(Optional.of(Instant.parse("2026-11-24T09:00:00Z")), Schedules.nextFire(Schedules.spec(r), r, NOW));
    assertEquals(Optional.empty(),
        Schedules.nextFire(Schedules.spec(r), r, Instant.parse("2026-11-24T09:00:00Z")));
  }
}

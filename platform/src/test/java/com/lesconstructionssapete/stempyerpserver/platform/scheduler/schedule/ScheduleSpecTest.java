package com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

class ScheduleSpecTest {

  private static final Instant ANCHOR = Instant.parse("2026-11-23T10:00:00Z");

  private static List<String> codes(ValidationFailedException e) {
    return e.fieldErrors().stream().map(f -> f.field() + ":" + f.code()).toList();
  }

  // ---- interval ----

  @Test
  void intervalIsAnchoredAndDoesNotDrift() {
    var spec = ScheduleSpec.of(ScheduleType.INTERVAL, null, "PT15M", null, ANCHOR);
    assertEquals(Instant.parse("2026-11-23T10:30:00Z"), spec.nextAfter(Instant.parse("2026-11-23T10:20:00Z")).orElseThrow());
    assertEquals(Instant.parse("2026-11-23T10:30:00Z"), spec.nextAfter(Instant.parse("2026-11-23T10:15:00Z")).orElseThrow());
    // far later: still on the 15-minute grid from the anchor
    assertEquals(Instant.parse("2026-12-24T07:45:00Z"), spec.nextAfter(Instant.parse("2026-12-24T07:31:12Z")).orElseThrow());
  }

  @Test
  void intervalBeforeItsAnchorFiresAtTheAnchor() {
    var spec = ScheduleSpec.of(ScheduleType.INTERVAL, "", "P1D", "", ANCHOR);
    assertEquals(ANCHOR, spec.nextAfter(ANCHOR.minusSeconds(3600)).orElseThrow());
    assertEquals(ANCHOR.plus(Duration.ofDays(1)), spec.nextAfter(ANCHOR).orElseThrow());
  }

  @Test
  void intervalValidation() {
    assertEquals(List.of("interval:REQUIRED"), codes(assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(ScheduleType.INTERVAL, null, " ", null, ANCHOR))));
    assertEquals(List.of("interval:INVALID_FORMAT"), codes(assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(ScheduleType.INTERVAL, null, "15 minutes", null, ANCHOR))));
    assertEquals(List.of("interval:OUT_OF_RANGE"), codes(assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(ScheduleType.INTERVAL, null, "PT30S", null, ANCHOR))));
    assertEquals(List.of("interval:OUT_OF_RANGE"), codes(assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(ScheduleType.INTERVAL, null, "PT1M0.5S", null, ANCHOR))));
    assertEquals(List.of("interval:OUT_OF_RANGE"), codes(assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(ScheduleType.INTERVAL, null, "P400D", null, ANCHOR))));
    assertEquals(List.of("cronExpression:NOT_ALLOWED"), codes(assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(ScheduleType.INTERVAL, "* * * * *", "PT15M", null, ANCHOR))));
  }

  // ---- cron ----

  @Test
  void cronUsesItsTimeZone() {
    var spec = ScheduleSpec.of(ScheduleType.CRON, "0 7 * * *", null, "America/Toronto", null);
    assertInstanceOf(ScheduleSpec.Cron.class, spec);
    // 10:00Z is 05:00 in Toronto (EST), so 07:00 local is later the same day, at 12:00Z
    assertEquals(Instant.parse("2026-11-23T12:00:00Z"), spec.nextAfter(ANCHOR).orElseThrow());
  }

  @Test
  void cronValidationReportsEveryProblem() {
    var e = assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(ScheduleType.CRON, "0 25 * * *", "PT1H", "Mars/Olympus", null));
    assertEquals(List.of("interval:NOT_ALLOWED", "cronExpression:INVALID_CRON", "timeZone:INVALID_TIME_ZONE"),
        codes(e));
    FieldError cron = e.fieldErrors().get(1);
    assertEquals("hour: value 25 is out of range 0-23", cron.message());
  }

  @Test
  void cronRequiresExpressionAndZone() {
    assertEquals(List.of("cronExpression:REQUIRED", "timeZone:REQUIRED"), codes(assertThrows(
        ValidationFailedException.class, () -> ScheduleSpec.of(ScheduleType.CRON, null, null, null, null))));
  }

  @Test
  void typeIsRequired() {
    assertEquals(List.of("scheduleType:REQUIRED"), codes(assertThrows(ValidationFailedException.class,
        () -> ScheduleSpec.of(null, "* * * * *", null, "UTC", null))));
  }

  // ---- window ----

  @Test
  void theWindowStartFiresOnceAndTheWindowEndStops() {
    var spec = ScheduleSpec.of(ScheduleType.INTERVAL, null, "PT15M", null, ANCHOR);
    Instant start = ANCHOR.plusSeconds(3600);
    Instant end = start.plusSeconds(1800);
    assertEquals(start, spec.nextFire(start, end, ANCHOR).orElseThrow());
    // asked again from the start itself: the next one, not the start again
    assertEquals(start.plusSeconds(900), spec.nextFire(start, end, start).orElseThrow());
    assertEquals(List.of(start, start.plusSeconds(900), start.plusSeconds(1800)), spec.preview(start, end, ANCHOR, 10));
  }

  // ---- preview and canonical form ----

  @Test
  void previewListsUpcomingFireTimes() {
    var spec = ScheduleSpec.of(ScheduleType.CRON, "0 9 * * MON", null, "UTC", null);
    assertEquals(List.of(Instant.parse("2026-11-30T09:00:00Z"), Instant.parse("2026-12-07T09:00:00Z"),
        Instant.parse("2026-12-14T09:00:00Z")), spec.preview(ANCHOR, 3));
  }

  @Test
  void canonicalFormChangesWithAnythingThatMovesFireTimes() {
    var a = ScheduleSpec.of(ScheduleType.CRON, "0 7 * * *", null, "UTC", null);
    assertEquals(a.canonical(), ScheduleSpec.of(ScheduleType.CRON, " 0 7  * * * ", null, "UTC", null).canonical());
    assertNotEquals(a.canonical(), ScheduleSpec.of(ScheduleType.CRON, "0 7 * * *", null, "America/Toronto", null).canonical());
    var i = ScheduleSpec.of(ScheduleType.INTERVAL, null, "PT15M", null, ANCHOR);
    assertNotEquals(i.canonical(), ScheduleSpec.of(ScheduleType.INTERVAL, null, "PT15M", null, ANCHOR.plusSeconds(60)).canonical());
  }
}

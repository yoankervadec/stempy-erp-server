package com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CronExpressionTest {

  private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

  /** Next fire time in UTC, as a local date-time string, for readable asserts. */
  private static String next(String cron, String afterUtc) {
    Instant after = LocalDateTime.parse(afterUtc).toInstant(ZoneOffset.UTC);
    return LocalDateTime.ofInstant(CronExpression.parse(cron).nextAfter(after, ZoneOffset.UTC).orElseThrow(),
        ZoneOffset.UTC).toString();
  }

  /** The next {@code count} fire times in UTC. */
  private static List<String> nextN(String cron, ZoneId zone, String afterUtc, int count) {
    CronExpression expression = CronExpression.parse(cron);
    Instant cursor = Instant.parse(afterUtc);
    List<String> times = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      cursor = expression.nextAfter(cursor, zone).orElseThrow();
      times.add(cursor.toString());
    }
    return times;
  }

  // ---- basic syntax ----

  @Test
  void everyMinuteIsStrictlyAfter() {
    assertEquals("2026-11-23T10:01", next("* * * * *", "2026-11-23T10:00:30"));
    assertEquals("2026-11-23T10:01", next("* * * * *", "2026-11-23T10:00"));
  }

  @Test
  void everyFifteenMinutes() {
    assertEquals("2026-11-23T10:15", next("*/15 * * * *", "2026-11-23T10:07"));
    assertEquals("2026-11-23T11:00", next("*/15 * * * *", "2026-11-23T10:45"));
  }

  @Test
  void dailyAtSevenRollsToTheNextDay() {
    assertEquals("2026-11-23T07:00", next("0 7 * * *", "2026-11-23T06:59"));
    assertEquals("2026-11-24T07:00", next("0 7 * * *", "2026-11-23T07:00"));
  }

  @Test
  void endOfYearRollsOver() {
    assertEquals("2027-01-01T00:00", next("0 0 * * *", "2026-12-31T23:59"));
  }

  @Test
  void everyTuesday() {
    // 2026-11-23 is a Monday
    assertEquals("2026-11-24T00:00", next("0 0 * * TUE", "2026-11-23T08:00"));
    assertEquals("2026-12-01T00:00", next("0 0 * * 2", "2026-11-24T00:00"));
  }

  @Test
  void weekdaysSkipTheWeekend() {
    // 2026-11-27 is a Friday
    assertEquals("2026-11-30T08:30", next("30 8 * * MON-FRI", "2026-11-27T09:00"));
  }

  @Test
  void rangeWithStepAndStartWithStep() {
    assertEquals(List.of("2026-11-23T10:10:00Z", "2026-11-23T10:30:00Z", "2026-11-23T10:50:00Z",
        "2026-11-23T11:10:00Z"), nextN("10-50/20 * * * *", ZoneOffset.UTC, "2026-11-23T10:00:00Z", 4));
    assertEquals(List.of("2026-11-23T10:05:00Z", "2026-11-23T10:20:00Z", "2026-11-23T10:35:00Z"),
        nextN("5/15 * * * *", ZoneOffset.UTC, "2026-11-23T10:00:00Z", 3));
  }

  @Test
  void listsAndMonthNames() {
    assertEquals("2027-01-01T00:00", next("0 0 1 JAN,JUL *", "2026-07-01T00:00"));
    assertEquals("2026-11-23T09:00", next("0 9,17 * * *", "2026-11-23T08:00"));
    assertEquals("2026-11-23T17:00", next("0 9,17 * * *", "2026-11-23T09:00"));
  }

  @Test
  void sundayIsBothZeroAndSeven() {
    // 2026-11-29 is a Sunday
    assertEquals("2026-11-29T00:00", next("0 0 * * 0", "2026-11-23T00:00"));
    assertEquals("2026-11-29T00:00", next("0 0 * * 7", "2026-11-23T00:00"));
    assertEquals("2026-11-29T00:00", next("0 0 * * SUN", "2026-11-23T00:00"));
    assertEquals(CronExpression.parse("0 0 * * 7").nextAfter(Instant.EPOCH, ZoneOffset.UTC),
        CronExpression.parse("0 0 * * 0").nextAfter(Instant.EPOCH, ZoneOffset.UTC));
  }

  @Test
  void namesAreCaseInsensitive() {
    assertEquals("2026-11-24T00:00", next("0 0 * * tue", "2026-11-23T08:00"));
    assertEquals("2026-12-01T00:00", next("0 0 1 dec *", "2026-11-23T08:00"));
  }

  @Test
  void questionMarkMeansAnyDay() {
    assertEquals("2026-11-24T00:00", next("0 0 ? * TUE", "2026-11-23T08:00"));
    assertEquals("2026-11-24T00:00", next("0 0 * * ?", "2026-11-23T08:00"));
  }

  @Test
  void macros() {
    assertEquals("2026-11-23T11:00", next("@hourly", "2026-11-23T10:30"));
    assertEquals("2026-11-24T00:00", next("@daily", "2026-11-23T10:30"));
    assertEquals("2026-11-24T00:00", next("@midnight", "2026-11-23T10:30"));
    assertEquals("2026-11-29T00:00", next("@weekly", "2026-11-23T10:30"));
    assertEquals("2026-12-01T00:00", next("@monthly", "2026-11-23T10:30"));
    assertEquals("2027-01-01T00:00", next("@YEARLY", "2026-11-23T10:30"));
    assertEquals(CronExpression.parse("@daily"), CronExpression.parse("0 0 * * *"));
  }

  @Test
  void canonicalFormIgnoresSpacingAndCase() {
    assertEquals("0 7 * * MON-FRI", CronExpression.parse("  0   7 * *\tmon-fri ").toString());
    assertEquals(CronExpression.parse("0 7 * * mon"), CronExpression.parse("0 7 * * MON"));
    assertNotEquals(CronExpression.parse("0 7 * * 1"), CronExpression.parse("0 7 * * MON"));
  }

  // ---- month ends, leap years, special days ----

  @Test
  void lastDayOfMonth() {
    assertEquals("2026-02-28T00:00", next("0 0 L * *", "2026-02-10T00:00"));
    assertEquals("2028-02-29T00:00", next("0 0 L * *", "2028-02-10T00:00"));
    assertEquals("2026-04-30T00:00", next("0 0 L * *", "2026-03-31T00:00"));
    assertEquals("2026-11-15T00:00", next("0 0 15,L * *", "2026-11-01T00:00"));
  }

  @Test
  void thirtyFirstSkipsShortMonths() {
    assertEquals("2026-05-31T00:00", next("0 0 31 * *", "2026-04-01T00:00"));
  }

  @Test
  void februaryTwentyNinthWaitsForALeapYear() {
    assertEquals("2028-02-29T00:00", next("0 0 29 2 *", "2026-03-01T00:00"));
  }

  @Test
  void nthWeekdayOfMonth() {
    // first Monday of December 2026 is the 7th (Dec 1 is a Tuesday)
    assertEquals("2026-12-07T09:00", next("0 9 * * MON#1", "2026-11-23T00:00"));
    assertEquals("2026-12-07T09:00", next("0 9 * * 1#1", "2026-11-23T00:00"));
    // third Friday of November 2026 is the 20th
    assertEquals("2026-11-20T09:00", next("0 9 * * FRI#3", "2026-11-01T00:00"));
  }

  @Test
  void lastWeekdayOfMonth() {
    // last Friday: 2026-11-27, then 2026-12-25
    assertEquals("2026-11-27T17:00", next("0 17 * * 5L", "2026-11-23T00:00"));
    assertEquals("2026-12-25T17:00", next("0 17 * * FRIL", "2026-11-27T17:00"));
  }

  @Test
  void restrictedDayOfMonthAndDayOfWeekAreOred() {
    // the 1st OR any Monday: 2026-11-23 (Mon) -> 11-30 (Mon) -> 12-01 (1st) -> 12-07 (Mon)
    assertEquals(List.of("2026-11-30T00:00:00Z", "2026-12-01T00:00:00Z", "2026-12-07T00:00:00Z"),
        nextN("0 0 1 * MON", ZoneOffset.UTC, "2026-11-23T00:00:00Z", 3));
  }

  @Test
  void starredDayOfMonthWithStepIsAndedWithDayOfWeek() {
    // */2 starts with *, so: odd days of the month that are also Mondays. 2026-11-23 Mon (odd),
    // 11-30 Mon (even, no), 12-07 Mon (odd)
    assertEquals(List.of("2026-11-23T00:00:00Z", "2026-12-07T00:00:00Z"),
        nextN("0 0 */2 * MON", ZoneOffset.UTC, "2026-11-22T00:00:00Z", 2));
  }

  // ---- invalid input ----

  @ParameterizedTest
  @ValueSource(strings = {
      "", "   ", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "0 0 0 * *", "0 0 32 * *",
      "* * * 0 *", "* * * 13 *", "* * * * 8", "5-1 * * * *", "*/0 * * * *", "*/60 * * * *", "a * * * *",
      "1,,2 * * * *", "1, * * * *", "-1 * * * *", "? * * * *", "* * L * *x", "* * * * MON#0",
      "* * * * MON#6", "* * * * FOO#1", "* * * * L", "* * * * XL", "@fortnightly", "0 0 * * MONDAY",
      "0 0 30 2 *", "0 0 31 4,6,9,11 *"
  })
  void rejectsInvalidExpressions(String cron) {
    assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(cron));
  }

  @Test
  void errorNamesTheField() {
    var e = assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 25 * * *"));
    assertEquals("hour: value 25 is out of range 0-23", e.getMessage());
    var never = assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 0 30 2 *"));
    assertTrue(never.getMessage().contains("never fires"), never.getMessage());
  }

  @Test
  void rejectsTooLongExpressions() {
    assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 0 * * " + "1,".repeat(60) + "2"));
  }

  // ---- time zones and DST (America/Toronto 2026: gap 03-08 02:00 -> 03:00, overlap 11-01 02:00 -> 01:00) ----

  @Test
  void localTimeIsKeptAcrossDst() {
    // 07:00 EDT = 11:00Z before the fall-back, 07:00 EST = 12:00Z after
    assertEquals(List.of("2026-10-31T11:00:00Z", "2026-11-01T12:00:00Z", "2026-11-02T12:00:00Z"),
        nextN("0 7 * * *", TORONTO, "2026-10-30T12:00:00Z", 3));
  }

  @Test
  void timeInTheSpringGapFiresAtTheEndOfTheGap() {
    // 02:30 does not exist on 2026-03-08: fires at 03:00 EDT (07:00Z), then 02:30 EDT the next day
    assertEquals(List.of("2026-03-07T07:30:00Z", "2026-03-08T07:00:00Z", "2026-03-09T06:30:00Z"),
        nextN("30 2 * * *", TORONTO, "2026-03-07T00:00:00Z", 3));
  }

  @Test
  void severalTimesInTheSpringGapFireOnce() {
    // after 01:45 EST (06:45Z): 02:00..02:45 are skipped, fire once at 03:00 EDT (07:00Z), then 03:15 EDT
    assertEquals(List.of("2026-03-08T07:00:00Z", "2026-03-08T07:15:00Z"),
        nextN("*/15 * * * *", TORONTO, "2026-03-08T06:45:00Z", 2));
  }

  @Test
  void repeatedTimeInTheFallOverlapFiresOnce() {
    // 01:30 happens twice on 2026-11-01: fires at the first (EDT, 05:30Z) only
    assertEquals(List.of("2026-11-01T05:30:00Z", "2026-11-02T06:30:00Z"),
        nextN("30 1 * * *", TORONTO, "2026-10-31T12:00:00Z", 2));
  }

  @Test
  void hourlyDuringTheOverlapSkipsTheRepeatedHour() {
    // 00:30 EDT, 01:30 EDT, (01:30 EST skipped), 02:30 EST
    assertEquals(List.of("2026-11-01T04:30:00Z", "2026-11-01T05:30:00Z", "2026-11-01T07:30:00Z"),
        nextN("30 * * * *", TORONTO, "2026-11-01T04:00:00Z", 3));
  }

  @Test
  void startingInsideTheSecondOccurrenceDoesNotGoBack() {
    // now is 01:10 EST (06:10Z); 01:30 already fired at its first occurrence -> next day
    assertEquals(List.of("2026-11-02T06:30:00Z"), nextN("30 1 * * *", TORONTO, "2026-11-01T06:10:00Z", 1));
  }
}

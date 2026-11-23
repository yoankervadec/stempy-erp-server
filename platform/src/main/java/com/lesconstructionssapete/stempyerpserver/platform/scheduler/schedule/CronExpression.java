package com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 5-field cron expression: minute hour day-of-month month day-of-week.
 *
 * <pre>
 * *  5  1-5  *&#47;15  10-50/10  1,15  JAN-MAR  MON-FRI     standard syntax; names are case-insensitive
 * day-of-week: 0 and 7 are Sunday
 * day-of-month: L       last day of the month
 * day-of-week:  MON#1   first Monday of the month (n = 1..5)
 *               5L      last Friday of the month (FRIL works too)
 * ?                     same as * (day-of-month and day-of-week only)
 * &#64;hourly &#64;daily &#64;midnight &#64;weekly &#64;monthly &#64;yearly &#64;annually
 * </pre>
 *
 * Day-of-month and day-of-week (standard Vixie cron rule): when both fields
 * are restricted, a day matching EITHER one fires ({@code 0 0 1 * MON} = the
 * 1st and every Monday). A field starting with {@code *} or {@code ?} counts
 * as unrestricted, and the two are then combined with AND
 * ({@code 0 0 *&#47;2 * MON} = Mondays falling on an odd day of the month).
 *
 * Times are local to the zone passed to {@link #nextAfter}:
 * <ul>
 * <li>a local time skipped by a spring-forward gap fires at the end of the
 * gap (02:30 fires at 03:00); several skipped times fire only once;</li>
 * <li>a local time repeated by a fall-back overlap fires once, at its first
 * occurrence. Use an INTERVAL schedule for "every N minutes of real
 * time".</li>
 * </ul>
 *
 * Immutable and thread-safe.
 */
public final class CronExpression {

  public static final int MAX_LENGTH = 100;

  /** next() gives up after this many years: the expression never fires. */
  static final int HORIZON_YEARS = 5;

  private static final Map<String, String> MACROS = Map.of(
      "@yearly", "0 0 1 1 *",
      "@annually", "0 0 1 1 *",
      "@monthly", "0 0 1 * *",
      "@weekly", "0 0 * * 0",
      "@daily", "0 0 * * *",
      "@midnight", "0 0 * * *",
      "@hourly", "0 * * * *");

  private static final Pattern NTH_WEEKDAY = Pattern.compile("(\\w+)#(\\d+)");
  private static final Pattern LAST_WEEKDAY = Pattern.compile("(\\w+)L", Pattern.CASE_INSENSITIVE);

  /** First local time of a window that contains two Feb 29ths, for the "never fires" check. */
  private static final LocalDateTime NEVER_FIRES_REFERENCE = LocalDateTime.of(2024, 1, 1, 0, 0);

  private record NthWeekday(int dayOfWeek, int nth) {
  }

  private final String canonical;
  private final BitSet minutes;
  private final BitSet hours;
  private final BitSet daysOfMonth;
  private final boolean lastDayOfMonth;
  private final BitSet months;
  private final BitSet daysOfWeek;
  private final BitSet lastWeekdaysOfMonth;
  private final List<NthWeekday> nthWeekdays;
  private final boolean dayOfMonthRestricted;
  private final boolean dayOfWeekRestricted;

  private CronExpression(String canonical, String[] f) {
    this.canonical = canonical;
    this.minutes = CronField.parse("minute", f[0], 0, 59, Map.of());
    this.hours = CronField.parse("hour", f[1], 0, 23, Map.of());
    this.months = CronField.parse("month", f[3], 1, 12, CronField.MONTH_NAMES);

    // day-of-month: peel off L, the rest is a plain field
    this.dayOfMonthRestricted = !isStar(f[2]);
    boolean last = false;
    List<String> plain = new ArrayList<>();
    for (String item : f[2].split(",", -1)) {
      if (item.equalsIgnoreCase("L")) {
        last = true;
      } else {
        plain.add(item.equals("?") ? "*" : item);
      }
    }
    this.lastDayOfMonth = last;
    this.daysOfMonth = plain.isEmpty() ? new BitSet()
        : CronField.parse("day-of-month", String.join(",", plain), 1, 31, Map.of());

    // day-of-week: peel off MON#1 and 5L, the rest is a plain field
    this.dayOfWeekRestricted = !isStar(f[4]);
    BitSet lastWeekdays = new BitSet(7);
    List<NthWeekday> nth = new ArrayList<>();
    plain.clear();
    for (String item : f[4].split(",", -1)) {
      Matcher m;
      if ((m = NTH_WEEKDAY.matcher(item)).matches()) {
        int n = Integer.parseInt(m.group(2));
        if (n < 1 || n > 5) {
          throw CronField.error("day-of-week", "'" + item + "': occurrence must be 1-5");
        }
        nth.add(new NthWeekday(dayOfWeek(m.group(1)), n));
      } else if ((m = LAST_WEEKDAY.matcher(item)).matches()) {
        lastWeekdays.set(dayOfWeek(m.group(1)));
      } else {
        plain.add(item.equals("?") ? "*" : item);
      }
    }
    BitSet dow = plain.isEmpty() ? new BitSet()
        : CronField.parse("day-of-week", String.join(",", plain), 0, 7, CronField.DAY_NAMES);
    if (dow.get(7)) {
      dow.set(0);
      dow.clear(7);
    }
    this.daysOfWeek = dow;
    this.lastWeekdaysOfMonth = lastWeekdays;
    this.nthWeekdays = List.copyOf(nth);
  }

  /**
   * Parses an expression.
   *
   * @throws IllegalArgumentException with a message naming the faulty field,
   *                                  also when the expression never fires
   *                                  (e.g. {@code 0 0 30 2 *})
   */
  public static CronExpression parse(String text) {
    Objects.requireNonNull(text, "text");
    String trimmed = text.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("cron expression is empty");
    }
    if (trimmed.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("cron expression is longer than " + MAX_LENGTH + " characters");
    }
    String expanded = trimmed;
    if (trimmed.startsWith("@")) {
      expanded = MACROS.get(trimmed.toLowerCase(Locale.ROOT));
      if (expanded == null) {
        throw new IllegalArgumentException("unknown macro " + trimmed
            + " (use @hourly, @daily, @midnight, @weekly, @monthly, @yearly or @annually)");
      }
    }
    String[] fields = expanded.split("\\s+");
    if (fields.length != 5) {
      throw new IllegalArgumentException("expected 5 fields (minute hour day-of-month month day-of-week), found "
          + fields.length);
    }
    var expression = new CronExpression(String.join(" ", fields).toUpperCase(Locale.ROOT), fields);
    if (expression.nextLocal(NEVER_FIRES_REFERENCE) == null) {
      throw new IllegalArgumentException("cron expression never fires (no match within "
          + HORIZON_YEARS + " years)");
    }
    return expression;
  }

  /**
   * The first fire time strictly after {@code after}, evaluated in
   * {@code zone}, or empty if there is none within {@value #HORIZON_YEARS}
   * years.
   */
  public Optional<Instant> nextAfter(Instant after, ZoneId zone) {
    Objects.requireNonNull(after, "after");
    var rules = Objects.requireNonNull(zone, "zone").getRules();
    LocalDateTime cursor = LocalDateTime.ofInstant(after, zone);
    while (true) {
      LocalDateTime candidate = nextLocal(cursor);
      if (candidate == null) {
        return Optional.empty();
      }
      Instant at;
      List<ZoneOffset> offsets = rules.getValidOffsets(candidate);
      if (offsets.isEmpty()) {
        at = rules.getTransition(candidate).getInstant(); // in a gap: the end of the gap
      } else {
        at = candidate.toInstant(offsets.get(0)); // in an overlap: the first occurrence
      }
      // A candidate can resolve to <= after near a transition (repeated or skipped
      // local times); move on past it, which is what makes those fire only once.
      if (at.isAfter(after)) {
        return Optional.of(at);
      }
      cursor = candidate;
    }
  }

  /** The first matching local time strictly after {@code after}, or null past the horizon. */
  LocalDateTime nextLocal(LocalDateTime after) {
    LocalDateTime t = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
    int lastYear = after.getYear() + HORIZON_YEARS;
    while (t.getYear() <= lastYear) {
      if (!months.get(t.getMonthValue())) {
        t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
        continue;
      }
      if (!dayMatches(t.toLocalDate())) {
        t = t.toLocalDate().plusDays(1).atStartOfDay();
        continue;
      }
      int hour = hours.nextSetBit(t.getHour());
      if (hour < 0) {
        t = t.toLocalDate().plusDays(1).atStartOfDay();
        continue;
      }
      if (hour != t.getHour()) {
        t = t.withHour(hour).withMinute(0);
      }
      int minute = minutes.nextSetBit(t.getMinute());
      if (minute < 0) {
        t = t.withMinute(0).plusHours(1);
        continue;
      }
      return t.withMinute(minute);
    }
    return null;
  }

  private boolean dayMatches(LocalDate date) {
    int dom = date.getDayOfMonth();
    int length = date.lengthOfMonth();
    boolean domMatch = daysOfMonth.get(dom) || (lastDayOfMonth && dom == length);

    int dow = date.getDayOfWeek().getValue() % 7; // Sunday = 0
    boolean dowMatch = daysOfWeek.get(dow)
        || (lastWeekdaysOfMonth.get(dow) && dom + 7 > length);
    for (NthWeekday n : nthWeekdays) {
      dowMatch |= n.dayOfWeek() == dow && (dom - 1) / 7 + 1 == n.nth();
    }

    // An unrestricted field matches every day, so AND reduces to the other field.
    return dayOfMonthRestricted && dayOfWeekRestricted ? domMatch || dowMatch : domMatch && dowMatch;
  }

  private static boolean isStar(String field) {
    return field.startsWith("*") || field.startsWith("?");
  }

  private static int dayOfWeek(String token) {
    int v = CronField.value("day-of-week", token, 0, 7, CronField.DAY_NAMES);
    return v == 7 ? 0 : v;
  }

  /**
   * The expression with macros expanded, single spaces and upper case: two
   * expressions that read the same have the same canonical form.
   */
  @Override
  public String toString() {
    return canonical;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CronExpression other && canonical.equals(other.canonical);
  }

  @Override
  public int hashCode() {
    return canonical.hashCode();
  }
}

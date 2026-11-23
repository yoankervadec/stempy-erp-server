package com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * When a schedule fires, independent of where it is stored. Pure: no clock,
 * no database. Build one from a row's columns with {@link #of}.
 */
public sealed interface ScheduleSpec {

  Duration MIN_INTERVAL = Duration.ofMinutes(1);
  Duration MAX_INTERVAL = Duration.ofDays(366);

  /** The first fire time strictly after {@code after}, or empty if it never fires again. */
  Optional<Instant> nextAfter(Instant after);

  /**
   * A stable text form of everything that decides the fire times; two specs
   * with the same canonical form fire at the same times.
   */
  String canonical();

  /** Up to {@code count} upcoming fire times after {@code after}, for previews. */
  default List<Instant> preview(Instant after, int count) {
    List<Instant> times = new ArrayList<>(count);
    Instant cursor = after;
    while (times.size() < count) {
      Optional<Instant> next = nextAfter(cursor);
      if (next.isEmpty()) {
        break;
      }
      cursor = next.get();
      times.add(cursor);
    }
    return times;
  }

  /** A cron expression evaluated in a time zone (07:00 stays 07:00 local across DST). */
  record Cron(CronExpression expression, ZoneId zone) implements ScheduleSpec {
    public Cron {
      Objects.requireNonNull(expression, "expression");
      Objects.requireNonNull(zone, "zone");
    }

    @Override
    public Optional<Instant> nextAfter(Instant after) {
      return expression.nextAfter(after, zone);
    }

    @Override
    public String canonical() {
      return "CRON|" + expression + "|" + zone.getId();
    }
  }

  /**
   * Fixed rate: fires at {@code anchor + k * every}. It does not drift and does
   * not depend on how long a run takes; fire times missed while down are handled
   * by the misfire policy, not replayed.
   */
  record Interval(Duration every, Instant anchor) implements ScheduleSpec {
    public Interval {
      Objects.requireNonNull(every, "every");
      Objects.requireNonNull(anchor, "anchor");
      if (every.compareTo(MIN_INTERVAL) < 0 || every.compareTo(MAX_INTERVAL) > 0 || every.getNano() != 0) {
        throw new IllegalArgumentException("interval must be whole seconds between " + MIN_INTERVAL
            + " and " + MAX_INTERVAL + ": " + every);
      }
    }

    @Override
    public Optional<Instant> nextAfter(Instant after) {
      if (after.isBefore(anchor)) {
        return Optional.of(anchor);
      }
      long elapsed = Duration.between(anchor, after).dividedBy(every);
      return Optional.of(anchor.plus(every.multipliedBy(elapsed + 1)));
    }

    @Override
    public String canonical() {
      return "INTERVAL|" + every + "|" + anchor;
    }
  }

  /**
   * Validates a schedule's columns and builds the spec. Field names are the
   * admin API's: scheduleType, cronExpression, interval, timeZone.
   *
   * @param anchor where an INTERVAL counts from (the row's start_at, else its
   *               created_at); unused for CRON
   * @throws ValidationFailedException listing every problem at once
   */
  static ScheduleSpec of(ScheduleType type, String cronExpression, String interval, String timeZone,
      Instant anchor) {
    var v = Validation.check().required("scheduleType", type);
    v.throwIfInvalid();
    String cron = blankToNull(cronExpression);
    String every = blankToNull(interval);

    return switch (type) {
      case CRON -> {
        v.required("cronExpression", cron);
        if (every != null) {
          v.add("interval", "NOT_ALLOWED", "must be empty for a CRON schedule");
        }
        v.required("timeZone", blankToNull(timeZone));
        CronExpression expression = null;
        if (cron != null) {
          try {
            expression = CronExpression.parse(cron);
          } catch (IllegalArgumentException e) {
            v.add("cronExpression", "INVALID_CRON", e.getMessage());
          }
        }
        ZoneId zone = null;
        if (blankToNull(timeZone) != null) {
          try {
            zone = ZoneId.of(timeZone.strip());
          } catch (DateTimeException e) {
            v.add("timeZone", "INVALID_TIME_ZONE", "must be an IANA time zone such as America/Toronto");
          }
        }
        v.throwIfInvalid();
        yield new Cron(expression, zone);
      }
      case INTERVAL -> {
        v.required("interval", every);
        if (cron != null) {
          v.add("cronExpression", "NOT_ALLOWED", "must be empty for an INTERVAL schedule");
        }
        Duration duration = null;
        if (every != null) {
          try {
            duration = Duration.parse(every);
          } catch (DateTimeException e) {
            v.add("interval", "INVALID_FORMAT", "must be an ISO-8601 duration such as PT15M or P1D");
          }
        }
        if (duration != null && (duration.compareTo(MIN_INTERVAL) < 0
            || duration.compareTo(MAX_INTERVAL) > 0 || duration.getNano() != 0)) {
          v.add("interval", "OUT_OF_RANGE", "must be whole seconds between PT1M and P366D");
        }
        v.throwIfInvalid();
        yield new Interval(duration, Objects.requireNonNull(anchor, "anchor"));
      }
    };
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.audit;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;

/**
 * The audit.* settings.
 *
 * @param retention entries older than this are deleted by the audit.prune
 *                  job; a Period (P7Y), since a Duration can't count years.
 *                  At least {@link #MIN_RETENTION}: CRA and Revenu Quebec
 *                  expect records kept 6 years after the year they relate to
 */
public record AuditSettings(Period retention) {

  public static final Period MIN_RETENTION = Period.ofYears(6);
  public static final Period DEFAULT_RETENTION = Period.ofYears(7);

  public AuditSettings {
    Objects.requireNonNull(retention, "retention");
    if (!atLeastMinimum(retention)) {
      throw new IllegalArgumentException("audit.retention must be at least " + MIN_RETENTION + ", was " + retention);
    }
  }

  public static AuditSettings from(Settings s) {
    return new AuditSettings(parse(s.get("audit.retention", DEFAULT_RETENTION.toString())));
  }

  public static AuditSettings defaults() {
    return new AuditSettings(DEFAULT_RETENTION);
  }

  /** ISO-8601 period (P7Y, P90M); IllegalArgumentException if malformed. */
  static Period parse(String raw) {
    try {
      return Period.parse(raw.strip());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("audit.retention must be an ISO-8601 period such as P7Y, was " + raw, e);
    }
  }

  /** Compared on the calendar (P72M equals P6Y; P5Y11M is not enough). */
  static boolean atLeastMinimum(Period retention) {
    LocalDate today = LocalDate.of(2000, 1, 1); // any fixed day: only the comparison matters
    return !today.minus(retention).isAfter(today.minus(MIN_RETENTION));
  }
}

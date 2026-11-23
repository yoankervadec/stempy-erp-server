package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleSpec;

/** Interpreting a ScheduleRow: its fingerprint, its spec, its next fire time. Pure. */
public final class Schedules {

  private Schedules() {
  }

  /**
   * SHA-256 (hex) of the raw columns that decide fire times. When it differs
   * from the stored schedule_fingerprint, next_run_at is stale and must be
   * recomputed. Raw text, not the parsed spec, so an invalid row also gets a
   * fingerprint (and is reported once per edit, not on every poll). A disabled
   * row has none: re-enabling it then starts from "now", instead of firing for
   * the time it was off.
   */
  public static String fingerprint(ScheduleRow r) {
    if (!r.enabled()) {
      return null;
    }
    String raw = String.join("|", String.valueOf(r.scheduleType()), String.valueOf(r.cronExpression()),
        String.valueOf(r.intervalValue()), String.valueOf(r.timeZone()), String.valueOf(r.startAt()),
        String.valueOf(r.endAt()), String.valueOf(r.createdAt()));
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /**
   * The row's spec. INTERVAL counts from start_at, else from created_at.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException
   *         if the stored columns are invalid
   */
  public static ScheduleSpec spec(ScheduleRow r) {
    Instant anchor = r.startAt() != null ? r.startAt() : r.createdAt();
    return ScheduleSpec.of(r.scheduleType(), r.cronExpression(), r.intervalValue(), r.timeZone(), anchor);
  }

  /**
   * The first fire time strictly after {@code after} inside the row's
   * [start_at, end_at] window, or empty if the schedule is over.
   */
  public static Optional<Instant> nextFire(ScheduleSpec spec, ScheduleRow r, Instant after) {
    Instant from = after;
    if (r.startAt() != null && !r.startAt().isBefore(after)) {
      from = r.startAt().minusSeconds(1); // so a fire exactly at start_at counts
    }
    Optional<Instant> next = spec.nextAfter(from);
    if (next.isPresent() && r.endAt() != null && next.get().isAfter(r.endAt())) {
      return Optional.empty();
    }
    return next;
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto;

import java.time.Instant;
import java.util.List;

/**
 * Upcoming fire times. Fewer than asked when the schedule ends (end time, or
 * no match within 5 years).
 *
 * @param timeZone the zone {@code local} is written in
 */
public record PreviewView(String timeZone, List<FireTime> times) {

  /** @param local the same instant in the schedule's zone, e.g. 2026-11-24T07:00-05:00 */
  public record FireTime(Instant at, String local) {
  }
}

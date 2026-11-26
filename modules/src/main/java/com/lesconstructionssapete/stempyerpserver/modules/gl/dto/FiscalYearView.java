package com.lesconstructionssapete.stempyerpserver.modules.gl.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A fiscal year and its 12 periods.
 *
 * @param name {@code 2026} for a calendar year, {@code 2026-2027} otherwise
 */
public record FiscalYearView(long id, String name, LocalDate startDate, LocalDate endDate, Instant createdAt,
    long createdBy, List<PeriodView> periods) {
}

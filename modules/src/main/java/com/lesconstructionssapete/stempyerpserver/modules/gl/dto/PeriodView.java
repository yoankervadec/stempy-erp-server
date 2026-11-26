package com.lesconstructionssapete.stempyerpserver.modules.gl.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.modules.gl.PeriodStatus;

/**
 * A period as the API returns it.
 *
 * @param name            {@code 2026-04}: the year and month it covers
 * @param statusChangedAt the last close or reopen; null if never
 */
public record PeriodView(long id, long fiscalYearId, int periodNo, String name, LocalDate startDate,
    LocalDate endDate, PeriodStatus status, Instant statusChangedAt, Long statusChangedBy) {
}

package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A gl_periods row.
 *
 * @param statusChangedAt the last close or reopen; null if never
 */
public record StoredPeriod(long id, long companyId, long fiscalYearId, int periodNo, LocalDate startDate,
    LocalDate endDate, PeriodStatus status, int version, Instant statusChangedAt, Long statusChangedBy) {
}

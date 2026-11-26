package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Instant;
import java.time.LocalDate;

/** A gl_fiscal_years row. */
public record StoredFiscalYear(long id, long companyId, LocalDate startDate, LocalDate endDate, Instant createdAt,
    long createdBy) {
}

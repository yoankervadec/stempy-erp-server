package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.time.Instant;

/**
 * A number_series row.
 *
 * @param used      it has at least one counter: pattern, mode and start value
 *                  are frozen
 * @param createdBy null: created by the server (a document type's default)
 */
public record StoredSeries(long id, int version, long companyId, String documentType, SeriesFields fields,
    boolean used, Instant createdAt, Long createdBy, Instant updatedAt, Long updatedBy) {
}

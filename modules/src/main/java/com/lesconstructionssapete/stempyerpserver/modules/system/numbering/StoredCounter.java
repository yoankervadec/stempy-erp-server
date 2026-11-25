package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.time.Instant;

/**
 * A number_series_counters row.
 *
 * @param scope      {@code STORE_NO=012;TERMINAL_NO=03}, or "" for a pattern
 *                   without parameters
 * @param lastNumber the last number issued; start value - 1 before the first
 */
public record StoredCounter(String scope, long lastNumber, Instant updatedAt) {
}

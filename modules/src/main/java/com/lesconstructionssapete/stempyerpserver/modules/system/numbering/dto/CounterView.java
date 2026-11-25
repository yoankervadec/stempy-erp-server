package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One counter of a series.
 *
 * @param scope      {@code STORE_NO=012;TERMINAL_NO=03}, or "" without parameters
 * @param params     the scope as values (padded)
 * @param nextNumber the counter value the next request gets
 * @param next       that number as it will print; null if the series' pattern
 *                   no longer fits its type
 */
public record CounterView(String scope, Map<String, String> params, long lastNumber, long nextNumber, String next,
    Instant updatedAt) {
}

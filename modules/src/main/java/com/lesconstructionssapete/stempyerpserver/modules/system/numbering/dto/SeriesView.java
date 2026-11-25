package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.dto;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;

/**
 * A number series as the API returns it.
 *
 * @param used          it has a counter: pattern, mode and start value are frozen
 * @param typeRegistered false when no module on this server declares the type
 *                      (the series can't issue numbers)
 * @param createdBy     null: created by the server, as the type's default
 */
public record SeriesView(long id, int version, String documentType, boolean typeRegistered, String code,
    String description, String pattern, Mode mode, long startValue, boolean isDefault, boolean isEnabled,
    boolean used, Instant createdAt, Long createdBy, Instant updatedAt, Long updatedBy) {
}

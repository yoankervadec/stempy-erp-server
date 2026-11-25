package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;
import com.lesconstructionssapete.stempyerpserver.platform.write.FieldChanges;

/**
 * The columns of a number series a user may set. Component names are the API
 * field names, so audit changes read as clients send them. The document type
 * is not here: it is fixed when the series is created.
 */
public record SeriesFields(
    String code,
    String description,
    String pattern,
    Mode mode,
    long startValue,
    boolean isDefault,
    boolean isEnabled) {

  /** The audit log's entity name for a series. */
  public static final String AUDIT_ENTITY = "numberSeries";

  /** The changes between two versions, for the audit log. {@code before} null: a creation. */
  public static Map<String, FieldChange> changes(SeriesFields before, SeriesFields after) {
    return FieldChanges.records(before, after);
  }

  /** A copy with another isDefault. */
  public SeriesFields withDefault(boolean value) {
    return new SeriesFields(code, description, pattern, mode, startValue, value, isEnabled);
  }
}

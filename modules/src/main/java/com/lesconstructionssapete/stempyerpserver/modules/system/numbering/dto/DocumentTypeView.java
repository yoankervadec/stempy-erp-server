package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.dto;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;

/**
 * A document type declared by a module: what a series of it may use.
 *
 * @param defaultSeries null when the type has none: a company must create a
 *                      series before its first number
 */
public record DocumentTypeView(String key, String description, List<String> parameters, boolean gaplessRequired,
    DefaultSeriesView defaultSeries) {

  /** The series a company gets on first use. */
  public record DefaultSeriesView(String code, String pattern, Mode mode) {
  }
}

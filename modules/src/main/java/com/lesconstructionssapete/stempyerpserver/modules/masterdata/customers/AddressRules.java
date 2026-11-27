package com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers;

import java.util.Set;
import java.util.regex.Pattern;

import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;

/**
 * What a Canadian address must look like (pure helper, no database): a
 * province or territory code, and a postal code written A1A 1A1. Addresses in
 * other countries are taken as typed.
 */
final class AddressRules {

  /** The 13 provinces and territories, as in tax_jurisdictions (CA-QC...). */
  static final Set<String> CANADIAN_PROVINCES = Set.of(
      "AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT");

  /** Canada Post's format; the letters D, F, I, O, Q, U never appear, W and Z never first. */
  private static final Pattern CANADIAN_POSTAL_CODE = Pattern.compile(
      "[ABCEGHJ-NPRSTVXY][0-9][ABCEGHJ-NPRSTV-Z] [0-9][ABCEGHJ-NPRSTV-Z][0-9]");

  private AddressRules() {
  }

  /**
   * Adds UNKNOWN_PROVINCE (param country) on {@code provinceField} and
   * INVALID_FORMAT on {@code postalField} for a Canadian address that breaks
   * the rules. Nothing for another country, or a missing value.
   */
  static void check(String country, String province, String postalCode, String provinceField, String postalField,
      Validation v) {
    if (!"CA".equals(country)) {
      return;
    }
    if (province != null && !CANADIAN_PROVINCES.contains(province)) {
      v.add(provinceField, "UNKNOWN_PROVINCE", "isn't a province or territory of " + country,
          Validation.params("country", country));
    }
    if (postalCode != null && !CANADIAN_POSTAL_CODE.matcher(postalCode).matches()) {
      v.add(postalField, "INVALID_FORMAT", "must be written A1A 1A1");
    }
  }
}

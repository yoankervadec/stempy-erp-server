package com.lesconstructionssapete.stempyerpserver.modules.company;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;

/**
 * The company itself (companies, docs/ARCHITECTURE.md §25.2): its settings
 * as the people working in it see them. The company column is the id, so
 * every read and write is scoped to the request's company: a caller sees
 * exactly one row, their own.
 *
 * Only the description and the default language change through the API. The
 * code, the base currency and the enabled flag are set when the company is
 * made (SQL today): amounts already posted are in the base currency, and
 * disabling the company would lock out everyone in it.
 */
public class Company {

  /** "fr|en": the languages the server writes in, as stored. */
  static final String LANGUAGE_PATTERN = Arrays.stream(Language.values()).map(Language::code)
      .collect(Collectors.joining("|"));

  public static final EntityDefinition<Company> DEF = EntityDefinition.of("company", "companies", Company::new)
      .companyColumn("id")
      .audit("created_at", "created_by", "updated_at", "updated_by");

  public static final Field<Company, Long> ID = DEF.id("id", Company::id, Company::setId);

  public static final Field<Company, Long> VERSION = DEF.version("version", Company::version, Company::setVersion);

  public static final Field<Company, String> CODE = DEF.string(
      "code", "code", Company::code, Company::setCode)
      .readOnly().filterable().sortable();

  public static final Field<Company, String> DESCRIPTION = DEF.string(
      "description", "description", Company::description, Company::setDescription)
      .maxLength(255).filterable().sortable();

  /** ISO 4217, e.g. CAD: what every amount of the company is in, unless it says otherwise. */
  public static final Field<Company, String> BASE_CURRENCY_CODE = DEF.string(
      "baseCurrencyCode", "base_currency_code", Company::baseCurrencyCode, Company::setBaseCurrencyCode)
      .readOnly().filterable();

  /** What the server writes in for users who chose no language of their own. */
  public static final Field<Company, String> DEFAULT_LANGUAGE = DEF.string(
      "defaultLanguage", "default_language", Company::defaultLanguage, Company::setDefaultLanguage)
      .required().pattern(LANGUAGE_PATTERN).filterable();

  public static final Field<Company, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", Company::isEnabled, Company::setEnabled)
      .readOnly();

  public static final Field<Company, Instant> CREATED_AT = DEF.instant(
      "createdAt", "created_at", Company::createdAt, Company::setCreatedAt).readOnly();

  public static final Field<Company, Long> CREATED_BY = DEF.integer(
      "createdBy", "created_by", Company::createdBy, Company::setCreatedBy).readOnly();

  public static final Field<Company, Instant> UPDATED_AT = DEF.instant(
      "updatedAt", "updated_at", Company::updatedAt, Company::setUpdatedAt).readOnly();

  public static final Field<Company, Long> UPDATED_BY = DEF.integer(
      "updatedBy", "updated_by", Company::updatedBy, Company::setUpdatedBy).readOnly();

  /** UI hints (docs/ARCHITECTURE.md §23.4). */
  static {
    DEF.titleField(CODE).subtitleField(DESCRIPTION)
        .listColumns(CODE, DESCRIPTION, BASE_CURRENCY_CODE, DEFAULT_LANGUAGE);
  }

  private Long id;
  private Long version;
  private String code;
  private String description;
  private String baseCurrencyCode;
  private String defaultLanguage;
  private boolean enabled;
  private Instant createdAt;
  private Long createdBy;
  private Instant updatedAt;
  private Long updatedBy;

  public Long id() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long version() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String code() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String description() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String baseCurrencyCode() {
    return baseCurrencyCode;
  }

  public void setBaseCurrencyCode(String baseCurrencyCode) {
    this.baseCurrencyCode = baseCurrencyCode;
  }

  public String defaultLanguage() {
    return defaultLanguage;
  }

  public void setDefaultLanguage(String defaultLanguage) {
    this.defaultLanguage = defaultLanguage;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Long createdBy() {
    return createdBy;
  }

  public void setCreatedBy(Long createdBy) {
    this.createdBy = createdBy;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Long updatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(Long updatedBy) {
    this.updatedBy = updatedBy;
  }
}

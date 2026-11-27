package com.lesconstructionssapete.stempyerpserver.modules.masterdata.currencies;

import java.util.stream.Collectors;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * A currency amounts can be in (currencies, docs/ARCHITECTURE.md §24).
 * Install-wide: every company shares the list. Plain master data: the generic
 * entity routes read and write it.
 *
 * The code is an ISO 4217 code with a minor unit (what shared.Money accepts:
 * CAD, USD, EUR, not XAU), unique and fixed once created, since
 * companies.base_currency_code and journal entries hold it by value.
 * Currencies are disabled, never deleted.
 */
public class Currency extends StandardEntity {

  /**
   * Every ISO 4217 code the JDK knows that has a minor unit, as one
   * alternation: anything else is INVALID_FORMAT before it reaches the
   * database.
   */
  static final String CODE_PATTERN = java.util.Currency.getAvailableCurrencies().stream()
      .filter(c -> c.getDefaultFractionDigits() >= 0)
      .map(java.util.Currency::getCurrencyCode)
      .sorted()
      .collect(Collectors.joining("|"));

  public static final EntityDefinition<Currency> DEF = Entities.defineGlobal("currency", "currencies",
      Currency::new);

  public static final Field<Currency, String> CODE = DEF.string(
      "code", "code", Currency::code, Currency::setCode)
      .required().unique().immutable().maxLength(3).pattern(CODE_PATTERN).filterable().sortable();

  public static final Field<Currency, String> NAME = DEF.string(
      "name", "name", Currency::name, Currency::setName)
      .required().maxLength(100).filterable().sortable();

  public static final Field<Currency, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", Currency::isEnabled, Currency::setEnabled)
      .filterable();

  /** UI hints (docs/ARCHITECTURE.md §23.4). */
  static {
    IS_ENABLED.defaultValue(true);
    DEF.subtitleField(NAME).listColumns(CODE, NAME, IS_ENABLED).defaultSort(SortKey.asc(CODE));
  }

  private String code;
  private String name;
  private boolean enabled = true;

  public String code() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.masterdata.units;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * A unit things are counted, sold or stocked in: UN, BOX, KG, M
 * (units_of_measure, docs/ARCHITECTURE.md §24). Install-wide: every company
 * shares the list. Plain master data: the generic entity routes read and
 * write it.
 *
 * The code is unique and can be corrected later (products point at the id).
 * Units are disabled, never deleted. No conversions between units yet.
 */
public class UnitOfMeasure extends StandardEntity {

  /** Upper case letters and digits, starting with a letter: UN, BOX, KG, M2. */
  public static final String CODE_PATTERN = "[A-Z][A-Z0-9]{0,9}";

  public static final EntityDefinition<UnitOfMeasure> DEF = Entities.defineGlobal("unitOfMeasure",
      "units_of_measure", UnitOfMeasure::new);

  public static final Field<UnitOfMeasure, String> CODE = DEF.string(
      "code", "code", UnitOfMeasure::code, UnitOfMeasure::setCode)
      .required().unique().maxLength(10).pattern(CODE_PATTERN).filterable().sortable();

  public static final Field<UnitOfMeasure, String> DESCRIPTION = DEF.string(
      "description", "description", UnitOfMeasure::description, UnitOfMeasure::setDescription)
      .required().maxLength(255).filterable().sortable();

  public static final Field<UnitOfMeasure, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", UnitOfMeasure::isEnabled, UnitOfMeasure::setEnabled)
      .filterable();

  /** UI hints (docs/ARCHITECTURE.md §23.4). */
  static {
    IS_ENABLED.defaultValue(true);
    DEF.subtitleField(DESCRIPTION).listColumns(CODE, DESCRIPTION, IS_ENABLED).defaultSort(SortKey.asc(CODE));
  }

  private String code;
  private String description;
  private boolean enabled = true;

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

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}

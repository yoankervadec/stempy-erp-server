package com.lesconstructionssapete.stempyerpserver.modules.gl;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * One value of a dimension: STORE = 012 "Downtown" (gl_dimension_values,
 * docs/ARCHITECTURE.md §22.3). Plain master data: the generic entity routes
 * read and write it. Its dimension and code are fixed once created; the code
 * is unique within its dimension (the database's unique key: a duplicate is
 * 409 DUPLICATE_VALUE). Values are disabled, never deleted.
 */
public class DimensionValue extends StandardEntity {

  /** Letters, digits, '.', '-', '_', starting with a letter or digit. */
  public static final String CODE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}";

  public static final EntityDefinition<DimensionValue> DEF = Entities.define("glDimensionValue",
      "gl_dimension_values", DimensionValue::new);

  public static final Field<DimensionValue, Long> DIMENSION_ID = DEF.integer(
      "dimensionId", "dimension_id", DimensionValue::dimensionId, DimensionValue::setDimensionId)
      .required().immutable().filterable().sortable();

  /** Lets clients filter and sort on the dimension, e.g. "dimension.code". */
  public static final Relation<DimensionValue, Dimension> DIMENSION_REL = DEF.manyToOne(
      "dimension", () -> Dimension.DEF, "dimension_id");

  public static final Field<DimensionValue, String> CODE = DEF.string(
      "code", "code", DimensionValue::code, DimensionValue::setCode)
      .required().immutable().maxLength(50).pattern(CODE_PATTERN).filterable().sortable();

  public static final Field<DimensionValue, String> NAME = DEF.string(
      "name", "name", DimensionValue::name, DimensionValue::setName)
      .required().maxLength(100).filterable().sortable();

  public static final Field<DimensionValue, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", DimensionValue::isEnabled, DimensionValue::setEnabled)
      .filterable();

  /**
   * UI hints (docs/ARCHITECTURE.md §23.4). The code is unique within its
   * dimension only, so it is declared as the title.
   */
  static {
    IS_ENABLED.defaultValue(true);
    DEF.titleField(CODE).subtitleField(NAME)
        .listColumns(DIMENSION_ID, CODE, NAME, IS_ENABLED)
        .defaultSort(SortKey.asc(DIMENSION_ID), SortKey.asc(CODE));
  }

  private Long dimensionId;
  private String code;
  private String name;
  private boolean enabled = true;

  public Long dimensionId() {
    return dimensionId;
  }

  public void setDimensionId(Long dimensionId) {
    this.dimensionId = dimensionId;
  }

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

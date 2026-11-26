package com.lesconstructionssapete.stempyerpserver.modules.gl;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * A dimension a company tags journal lines with: STORE, PROJECT, DEPARTMENT...
 * (gl_dimensions, docs/ARCHITECTURE.md §22.3). Plain master data: the generic
 * entity routes read and write it. The code is unique in the company and
 * fixed once created; dimensions are disabled, never deleted.
 */
public class Dimension extends StandardEntity {

  /** Upper case, digits and underscores, starting with a letter. */
  public static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{0,19}";

  public static final EntityDefinition<Dimension> DEF = Entities.define("glDimension", "gl_dimensions",
      Dimension::new);

  public static final Field<Dimension, String> CODE = DEF.string(
      "code", "code", Dimension::code, Dimension::setCode)
      .required().unique().immutable().maxLength(20).pattern(CODE_PATTERN).filterable().sortable();

  public static final Field<Dimension, String> NAME = DEF.string(
      "name", "name", Dimension::name, Dimension::setName)
      .required().maxLength(100).filterable().sortable();

  public static final Field<Dimension, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", Dimension::isEnabled, Dimension::setEnabled)
      .filterable();

  /** UI hints (docs/ARCHITECTURE.md §23.4). */
  static {
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

package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

public class ProductCategory extends StandardEntity {

  private boolean isEnabled;
  private String code;
  private String description;

  public static final EntityDefinition<ProductCategory> DEF = Entities.define(
      "productCategory", "product_categories", ProductCategory::new);

  public static final Field<ProductCategory, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", ProductCategory::isEnabled, ProductCategory::setEnabled)
      .filterable();

  public static final Field<ProductCategory, String> CODE = DEF.string(
      "code", "code", ProductCategory::code, ProductCategory::setCode)
      .filterable().sortable();

  public static final Field<ProductCategory, String> DESCRIPTION = DEF.string(
      "description", "description", ProductCategory::description, ProductCategory::setDescription)
      .filterable().sortable();

  public boolean isEnabled() {
    return isEnabled;
  }

  public void setEnabled(boolean isEnabled) {
    this.isEnabled = isEnabled;
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

}

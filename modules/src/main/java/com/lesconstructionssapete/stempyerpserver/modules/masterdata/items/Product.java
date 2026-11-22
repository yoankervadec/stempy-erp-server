package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import java.math.BigDecimal;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

public class Product extends StandardEntity {

  private String productNo;
  private String name;
  private Long unitOfMeasureId;
  private BigDecimal unitAmount;
  private Long currencyId;
  private Long productCategoryId;
  private boolean isEnabled;

  public static final EntityDefinition<Product> DEF = Entities.define(
      "product", "products", Product::new);

  public static final Field<Product, String> PRODUCT_NO = DEF.string(
      "productNo", "product_no", Product::productNo, Product::setProductNo)
      .filterable().sortable();

  public static final Field<Product, String> NAME = DEF.string(
      "name", "name", Product::name, Product::setName)
      .filterable().sortable();

  public static final Field<Product, Long> UNIT_OF_MEASURE_ID = DEF.integer(
      "unitOfMeasureId", "unit_of_measure_id", Product::unitOfMeasureId, Product::setUnitOfMeasureId);

  public static final Field<Product, BigDecimal> UNIT_AMOUNT = DEF.decimal(
      "unitAmount", "unit_amount", Product::unitAmount, Product::setUnitAmount)
      .filterable().sortable();

  public static final Field<Product, Long> CURRENCY_ID = DEF.integer(
      "currencyId", "currency_id", Product::currencyId, Product::setCurrencyId);

  public static final Field<Product, Long> PRODUCT_CATEGORY_ID = DEF.integer(
      "productCategoryId", "product_category_id", Product::productCategoryId, Product::setProductCategoryId)
      .filterable();

  /** Lets clients filter and sort on the category, e.g. "category.code". */
  public static final Relation<Product, ProductCategory> CATEGORY_REL = DEF.manyToOne(
      "category", () -> ProductCategory.DEF, "product_category_id");

  public static final Field<Product, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", Product::isEnabled, Product::setEnabled)
      .filterable();

  public String productNo() {
    return productNo;
  }

  public void setProductNo(String productNo) {
    this.productNo = productNo;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Long unitOfMeasureId() {
    return unitOfMeasureId;
  }

  public void setUnitOfMeasureId(Long unitOfMeasureId) {
    this.unitOfMeasureId = unitOfMeasureId;
  }

  public BigDecimal unitAmount() {
    return unitAmount;
  }

  public void setUnitAmount(BigDecimal unitAmount) {
    this.unitAmount = unitAmount;
  }

  public Long currencyId() {
    return currencyId;
  }

  public void setCurrencyId(Long currencyId) {
    this.currencyId = currencyId;
  }

  public Long productCategoryId() {
    return productCategoryId;
  }

  public void setProductCategoryId(Long productCategoryId) {
    this.productCategoryId = productCategoryId;
  }

  public boolean isEnabled() {
    return isEnabled;
  }

  public void setEnabled(boolean isEnabled) {
    this.isEnabled = isEnabled;
  }
}

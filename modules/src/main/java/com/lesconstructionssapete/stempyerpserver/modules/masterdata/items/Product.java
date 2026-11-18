package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import java.math.BigDecimal;
import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.users.User;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;

public class Product {

  private Long id;
  private Long companyId;
  private Long version;
  private Instant createdAt;
  private Long createdBy;
  private Instant updatedAt;
  private Long updatedBy;
  private Instant deletedAt;

  private String productNo;
  private String name;
  private Long unitOfMeasureId;
  private BigDecimal unitAmount;
  private Long currencyId;
  private Long productCategoryId;
  private boolean isEnabled;

  public static final EntityDefinition<Product> DEF = EntityDefinition.of(
      "product", "products", Product::new)
      .softDelete("deleted_at")
      .updatedAt("updated_at");

  public static final Field<Product, Long> ID = DEF.id(
      "id", Product::id, Product::setId);

  public static final Field<Product, Long> COMPANY_ID = DEF.companyId(
      Product::companyId, Product::setCompanyId);

  public static final Field<Product, Long> VERSION = DEF.version(
      "version", Product::version, Product::setVersion);

  public static final Field<Product, Instant> CREATED_AT = DEF.instant(
      "createdAt", "created_at", Product::createdAt, Product::setCreatedAt);

  public static final Field<Product, Long> CREATED_BY = DEF.integer(
      "createdBy", "created_by", Product::createdBy, Product::setCreatedBy);

  public static final Relation<Product, User> CREATED_BY_REL = DEF.manyToOne(
      "createdByUser", () -> User.DEF, "created_by");

  public static final Field<Product, Instant> UPDATED_AT = DEF.instant(
      "updatedAt", "updated_at", Product::updatedAt, Product::setUpdatedAt);

  public static final Field<Product, Long> UPDATED_BY = DEF.integer(
      "updatedBy", "updated_by", Product::updatedBy, Product::setUpdatedBy);

  public static final Relation<Product, User> UPDATED_BY_REL = DEF.manyToOne(
      "updatedByUser", () -> User.DEF, "updated_by");

  public static final Field<Product, Instant> DELETED_AT = DEF.instant(
      "deletedAt", "deleted_at", Product::deletedAt, Product::setDeletedAt);

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

  public Long id() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long companyId() {
    return companyId;
  }

  public void setCompanyId(Long companyId) {
    this.companyId = companyId;
  }

  public Long version() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
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

  public Instant deletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

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

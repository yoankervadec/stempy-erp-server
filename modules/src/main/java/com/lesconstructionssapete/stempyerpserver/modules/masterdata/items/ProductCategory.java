package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.users.User;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;

public class ProductCategory {

  private Long id;
  private Long companyId;
  private Long version;
  private Instant createdAt;
  private Long createdBy;
  private Instant updatedAt;
  private Long updatedBy;
  private Instant deletedAt;

  private boolean isEnabled;
  private String code;
  private String description;

  public static final EntityDefinition<ProductCategory> DEF = EntityDefinition.of(
      "productCategory", "product_categories", ProductCategory::new)
      .softDelete("deleted_at")
      .updatedAt("updated_at");

  public static final Field<ProductCategory, Long> ID = DEF.id(
      "id", ProductCategory::id, ProductCategory::setId);

  public static final Field<ProductCategory, Long> COMPANY_ID = DEF.companyId(
      ProductCategory::companyId, ProductCategory::setCompanyId);

  public static final Field<ProductCategory, Long> VERSION = DEF.version(
      "version", ProductCategory::version, ProductCategory::setVersion);

  public static final Field<ProductCategory, Instant> CREATED_AT = DEF.instant(
      "createdAt", "created_at", ProductCategory::createdAt, ProductCategory::setCreatedAt);

  public static final Field<ProductCategory, Long> CREATED_BY = DEF.integer(
      "createdBy", "created_by", ProductCategory::createdBy, ProductCategory::setCreatedBy);
  public static final Relation<ProductCategory, User> CREATED_BY_REL = DEF.manyToOne(
      "createdByUser", () -> User.DEF, "created_by");

  public static final Field<ProductCategory, Instant> UPDATED_AT = DEF.instant(
      "updatedAt", "updated_at", ProductCategory::updatedAt, ProductCategory::setUpdatedAt);

  public static final Field<ProductCategory, Long> UPDATED_BY = DEF.integer(
      "updatedBy", "updated_by", ProductCategory::updatedBy, ProductCategory::setUpdatedBy);

  public static final Relation<ProductCategory, User> UPDATED_BY_REL = DEF.manyToOne(
      "updatedByUser", () -> User.DEF, "updated_by");

  public static final Field<ProductCategory, Instant> DELETED_AT = DEF.instant(
      "deletedAt", "deleted_at", ProductCategory::deletedAt, ProductCategory::setDeletedAt);

  public static final Field<ProductCategory, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", ProductCategory::isEnabled, ProductCategory::setEnabled)
      .filterable();

  public static final Field<ProductCategory, String> CODE = DEF.string(
      "code", "code", ProductCategory::code, ProductCategory::setCode)
      .filterable().sortable();

  public static final Field<ProductCategory, String> DESCRIPTION = DEF.string(
      "description", "description", ProductCategory::description, ProductCategory::setDescription)
      .filterable().sortable();

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

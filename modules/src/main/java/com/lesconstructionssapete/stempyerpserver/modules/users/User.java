package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;

public class User {

  private Long id;
  private Long companyId;
  private Long version;
  private Instant createdAt;
  private Long createdBy;
  private Instant updatedAt;
  private Long updatedBy;
  private Instant deletedAt;

  private boolean isEnabled;
  private String userNo;
  private String name;

  public static final EntityDefinition<User> DEF = EntityDefinition.of(
      "user", "users", User::new)
      .softDelete("deleted_at")
      .updatedAt("updated_at");

  public static final Field<User, Long> ID = DEF.id(
      "id", User::id, User::setId);

  public static final Field<User, Long> COMPANY_ID = DEF.companyId(
      User::companyId, User::setCompanyId);

  public static final Field<User, Long> VERSION = DEF.version(
      "version", User::version, User::setVersion);

  public static final Field<User, Instant> CREATED_AT = DEF.instant(
      "createdAt", "created_at", User::createdAt, User::setCreatedAt);

  public static final Field<User, Long> CREATED_BY = DEF.integer(
      "createdBy", "created_by", User::createdBy, User::setCreatedBy);

  public static final Field<User, Instant> UPDATED_AT = DEF.instant(
      "updatedAt", "updated_at", User::updatedAt, User::setUpdatedAt);

  public static final Field<User, Long> UPDATED_BY = DEF.integer(
      "updatedBy", "updated_by", User::updatedBy, User::setUpdatedBy);

  public static final Field<User, Instant> DELETED_AT = DEF.instant(
      "deletedAt", "deleted_at", User::deletedAt, User::setDeletedAt);

  public static final Field<User, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", User::isEnabled, User::setEnabled);

  public static final Field<User, String> USER_NO = DEF.string(
      "userNo", "user_no", User::userNo, User::setUserNo)
      .filterable().sortable();

  public static final Field<User, String> NAME = DEF.string(
      "name", "name", User::name, User::setName)
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

  public String userNo() {
    return userNo;
  }

  public void setUserNo(String userNo) {
    this.userNo = userNo;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}

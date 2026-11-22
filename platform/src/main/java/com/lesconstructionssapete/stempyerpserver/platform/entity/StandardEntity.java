package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Base class for entities on a standard business table, i.e. one with the
 * columns: id, company_id, version, created_at, created_by, updated_at,
 * updated_by, deleted_at.
 *
 * Build the DEF with {@link #define}, which declares those eight fields and
 * the soft-delete / updated-at behaviour; the entity then declares only its
 * own columns:
 *
 * <pre>
 * public class Product extends StandardEntity {
 *   public static final EntityDefinition&lt;Product&gt; DEF = StandardEntity.define("product", "products", Product::new);
 *   public static final Field&lt;Product, String&gt; NAME = DEF.string("name", "name", Product::name, Product::setName);
 *   ...
 * }
 * </pre>
 *
 * Install-wide tables with the same columns minus company_id use
 * {@link #defineGlobal}; tables shaped differently (link tables) use
 * EntityDefinition.of directly.
 */
public abstract class StandardEntity {

  private Long id;
  private Long companyId;
  private Long version;
  private Instant createdAt;
  private Long createdBy;
  private Instant updatedAt;
  private Long updatedBy;
  private Instant deletedAt;

  /** A definition with the standard fields already declared. */
  public static <E extends StandardEntity> EntityDefinition<E> define(String name, String table, Supplier<E> factory) {
    return standard(EntityDefinition.of(name, table, factory), true);
  }

  /**
   * For an install-wide table shared by every company (e.g. users): the
   * standard columns minus company_id; companyId() stays null.
   */
  public static <E extends StandardEntity> EntityDefinition<E> defineGlobal(String name, String table,
      Supplier<E> factory) {
    return standard(EntityDefinition.of(name, table, factory).globalTable(), false);
  }

  private static <E extends StandardEntity> EntityDefinition<E> standard(EntityDefinition<E> def, boolean company) {
    def.softDelete("deleted_at").audit("created_at", "created_by", "updated_at", "updated_by");
    def.id("id", StandardEntity::id, StandardEntity::setId);
    if (company) {
      def.companyId(StandardEntity::companyId, StandardEntity::setCompanyId);
    }
    def.version("version", StandardEntity::version, StandardEntity::setVersion);
    // Server-managed: clients read them, never write them.
    def.instant("createdAt", "created_at", StandardEntity::createdAt, StandardEntity::setCreatedAt).readOnly();
    def.integer("createdBy", "created_by", StandardEntity::createdBy, StandardEntity::setCreatedBy).readOnly();
    def.instant("updatedAt", "updated_at", StandardEntity::updatedAt, StandardEntity::setUpdatedAt).readOnly();
    def.integer("updatedBy", "updated_by", StandardEntity::updatedBy, StandardEntity::setUpdatedBy).readOnly();
    def.instant("deletedAt", "deleted_at", StandardEntity::deletedAt, StandardEntity::setDeletedAt).readOnly();
    return def;
  }

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
}

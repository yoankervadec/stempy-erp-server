package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.users.dto.CompanySummary;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * Memberships (user_companies), role assignments and permission overrides:
 * who may work in which company, with what. Raw JDBC, no rules.
 *
 * "Accessible" always means: an enabled membership in an enabled company.
 */
public class AccessRepository {

  public record CompanyRow(CompanySummary company, boolean isDefault) {
  }

  /** What roles give a user in one company. */
  public record RoleGrants(boolean allPermissions, Set<String> permissions) {
  }

  public record Overrides(Set<String> granted, Set<String> denied) {
  }

  /** The companies the user may work in, by code. */
  public List<CompanyRow> accessibleCompanies(UnitOfWork uow, long userId) {
    return Jdbc.queryList(uow, """
        SELECT c.id, c.code, c.description, (u.default_company_id <=> c.id) AS is_default
        FROM user_companies uc
        JOIN companies c ON c.id = uc.company_id
        JOIN users u ON u.id = uc.user_id
        WHERE uc.user_id = ? AND uc.is_enabled AND c.is_enabled
        ORDER BY c.code""",
        rs -> new CompanyRow(new CompanySummary(rs.getLong("id"), rs.getString("code"), rs.getString("description")),
            rs.getBoolean("is_default")),
        userId);
  }

  public boolean canAccess(UnitOfWork uow, long userId, long companyId) {
    return Jdbc.queryFirst(uow, """
        SELECT 1 FROM user_companies uc JOIN companies c ON c.id = uc.company_id
        WHERE uc.user_id = ? AND uc.company_id = ? AND uc.is_enabled AND c.is_enabled""",
        rs -> true, userId, companyId).isPresent();
  }

  /** Union over the user's enabled, non-deleted roles in this company. */
  public RoleGrants roleGrants(UnitOfWork uow, long userId, long companyId) {
    boolean[] all = { false };
    Set<String> permissions = new HashSet<>();
    Jdbc.queryList(uow, """
        SELECT r.all_permissions, rp.permission
        FROM user_company_roles ucr
        JOIN roles r ON r.id = ucr.role_id AND r.is_enabled AND r.deleted_at IS NULL
        LEFT JOIN role_permissions rp ON rp.role_id = r.id
        WHERE ucr.user_id = ? AND ucr.company_id = ?""",
        rs -> {
          all[0] |= rs.getBoolean("all_permissions");
          String p = rs.getString("permission");
          if (p != null) {
            permissions.add(p);
          }
          return null;
        },
        userId, companyId);
    return new RoleGrants(all[0], permissions);
  }

  public Overrides overrides(UnitOfWork uow, long userId, long companyId) {
    Set<String> granted = new HashSet<>();
    Set<String> denied = new HashSet<>();
    Jdbc.queryList(uow, "SELECT permission, effect FROM user_permission_overrides WHERE user_id = ? AND company_id = ?",
        rs -> ("DENY".equals(rs.getString("effect")) ? denied : granted).add(rs.getString("permission")),
        userId, companyId);
    return new Overrides(granted, denied);
  }

  // ---- administration ----

  public Optional<Long> findCompanyIdByCode(UnitOfWork uow, String code) {
    return Jdbc.queryFirst(uow, "SELECT id FROM companies WHERE code = ?", rs -> rs.getLong("id"), code);
  }

  public Optional<Long> findRoleIdByCode(UnitOfWork uow, String code) {
    return Jdbc.queryFirst(uow, "SELECT id FROM roles WHERE code = ? AND deleted_at IS NULL",
        rs -> rs.getLong("id"), code);
  }

  /** Empty: no membership; else whether it is enabled. */
  public Optional<Boolean> membership(UnitOfWork uow, long userId, long companyId) {
    return Jdbc.queryFirst(uow, "SELECT is_enabled FROM user_companies WHERE user_id = ? AND company_id = ?",
        rs -> rs.getBoolean("is_enabled"), userId, companyId);
  }

  public boolean hasRole(UnitOfWork uow, long userId, long companyId, long roleId) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM user_company_roles WHERE user_id = ? AND company_id = ? AND role_id = ?",
        rs -> true, userId, companyId, roleId).isPresent();
  }

  /** Adds the membership, or re-enables it if it was disabled. */
  public void grantMembership(UnitOfWork uow, long userId, long companyId, Long actingUserId, Instant now) {
    Jdbc.update(uow, """
        INSERT INTO user_companies (user_id, company_id, is_enabled, created_at, created_by)
        VALUES (?, ?, TRUE, ?, ?)
        ON DUPLICATE KEY UPDATE is_enabled = TRUE""",
        userId, companyId, now, actingUserId);
  }

  /** Only when the user has no default yet. */
  public void setDefaultCompanyIfMissing(UnitOfWork uow, long userId, long companyId) {
    Jdbc.update(uow, "UPDATE users SET default_company_id = ? WHERE id = ? AND default_company_id IS NULL",
        companyId, userId);
  }

  /** Idempotent. */
  public void assignRole(UnitOfWork uow, long userId, long companyId, long roleId, Long actingUserId, Instant now) {
    Jdbc.update(uow, """
        INSERT INTO user_company_roles (user_id, company_id, role_id, created_at, created_by)
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE role_id = role_id""",
        userId, companyId, roleId, now, actingUserId);
  }
}

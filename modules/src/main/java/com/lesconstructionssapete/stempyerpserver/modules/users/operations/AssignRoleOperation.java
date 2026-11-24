package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Gives a user a role in a company. Adds (or re-enables) the membership first,
 * and makes that company the user's default if they had none. Idempotent.
 * Returns the user's id.
 *
 * Audited (entity "user", in that company): MEMBERSHIP_GRANTED when the
 * membership is new or re-enabled, ROLE_ASSIGNED when the role is new. A
 * repeat that changes nothing records nothing.
 */
public class AssignRoleOperation implements Operation<AssignRoleOperation.Input, Long> {

  /**
   * @param actingUserId null when run from the command line
   * @param requestId    null when run from the command line
   */
  public record Input(String userNo, String companyCode, String roleCode, Long actingUserId, String requestId,
      Instant now) {
  }

  private final UserAccountRepository accounts;
  private final AccessRepository access;
  private final AuditLog audit;

  public AssignRoleOperation(UserAccountRepository accounts, AccessRepository access, AuditLog audit) {
    this.accounts = accounts;
    this.access = access;
    this.audit = audit;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    long userId = accounts.findByUserNo(uow, in.userNo())
        .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "No user with number " + in.userNo()))
        .id();
    long companyId = access.findCompanyIdByCode(uow, in.companyCode())
        .orElseThrow(() -> new ResourceNotFoundException("COMPANY_NOT_FOUND", "No company with code " + in.companyCode()));
    long roleId = access.findRoleIdByCode(uow, in.roleCode())
        .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "No role with code " + in.roleCode()));

    Optional<Boolean> membership = access.membership(uow, userId, companyId);
    boolean hadRole = access.hasRole(uow, userId, companyId, roleId);
    access.grantMembership(uow, userId, companyId, in.actingUserId(), in.now());
    access.setDefaultCompanyIfMissing(uow, userId, companyId);
    access.assignRole(uow, userId, companyId, roleId, in.actingUserId(), in.now());

    if (!membership.orElse(false)) {
      record(uow, in, userId, companyId, "MEMBERSHIP_GRANTED", "enabled", membership.orElse(null), true);
    }
    if (!hadRole) {
      record(uow, in, userId, companyId, "ROLE_ASSIGNED", "role", null, in.roleCode());
    }
    return userId;
  }

  private void record(UnitOfWork uow, Input in, long userId, long companyId, String action, String field,
      Object oldValue, Object newValue) {
    audit.record(uow, new AuditEntry("user", userId, companyId, action, in.actingUserId(), in.requestId(), in.now(),
        Map.of(field, new FieldChange(oldValue, newValue))));
  }
}

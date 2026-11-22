package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;

/**
 * Gives a user a role in a company. Adds (or re-enables) the membership first,
 * and makes that company the user's default if they had none. Idempotent.
 * Returns the user's id.
 */
public class AssignRoleOperation implements Operation<AssignRoleOperation.Input, Long> {

  /** @param actingUserId null when run from the command line */
  public record Input(String userNo, String companyCode, String roleCode, Long actingUserId, Instant now) {
  }

  private final UserAccountRepository accounts;
  private final AccessRepository access;

  public AssignRoleOperation(UserAccountRepository accounts, AccessRepository access) {
    this.accounts = accounts;
    this.access = access;
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

    access.grantMembership(uow, userId, companyId, in.actingUserId(), in.now());
    access.setDefaultCompanyIfMissing(uow, userId, companyId);
    access.assignRole(uow, userId, companyId, roleId, in.actingUserId(), in.now());
    return userId;
  }
}

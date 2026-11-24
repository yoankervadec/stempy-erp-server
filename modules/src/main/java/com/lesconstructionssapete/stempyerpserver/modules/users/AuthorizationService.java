package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Clock;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.AssignRoleOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.security.CachedCompanyAccess;

/**
 * ORCHESTRATOR for changes to who may do what: memberships, roles, overrides.
 * Invalidates the access cache AFTER each commit, so the next request sees
 * the change (and a rolled-back change never evicts anything).
 */
public class AuthorizationService {

  private final UnitOfWorkFactory uowFactory;
  private final CachedCompanyAccess cache;
  private final Clock clock;
  private final AssignRoleOperation assignRole;

  public AuthorizationService(UnitOfWorkFactory uowFactory, CachedCompanyAccess cache, Clock clock,
      AssignRoleOperation assignRole) {
    this.uowFactory = uowFactory;
    this.cache = cache;
    this.clock = clock;
    this.assignRole = assignRole;
  }

  /** @param actingUserId null when run from the command line */
  public void assignRole(String userNo, String companyCode, String roleCode, Long actingUserId) {
    long userId = uowFactory.execute(uow -> assignRole.execute(uow,
        new AssignRoleOperation.Input(trimmed(userNo), trimmed(companyCode), trimmed(roleCode), actingUserId,
            null, clock.instant())));
    cache.invalidateUser(userId);
  }

  private static String trimmed(String s) {
    return s == null ? null : s.trim();
  }
}

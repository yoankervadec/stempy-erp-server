package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository.UserRef;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * Finds the SYSTEM user, creating it if missing: disabled, no credentials, its
 * own creator. Safe when several server instances start at once: the insert
 * is ignored if another one won, and the row is read back.
 */
public class EnsureSystemUserOperation implements Operation<Void, UserRef> {

  private static final int ATTEMPTS = 3;

  private final UserAccountRepository accounts;

  public EnsureSystemUserOperation(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public UserRef execute(UnitOfWork uow, Void input) {
    for (int i = 0; i < ATTEMPTS; i++) {
      var existing = accounts.findAnyByUserNo(uow, SystemActor.USER_NO);
      if (existing.isPresent()) {
        return existing.get();
      }
      // Ignored if a concurrent insert took the same id; the next pass then sees
      // SYSTEM (if that insert was it) or picks the following id.
      accounts.insertSelfCreatedDisabledUser(uow, SystemActor.USER_NO, "System");
    }
    return accounts.findAnyByUserNo(uow, SystemActor.USER_NO)
        .orElseThrow(() -> new IllegalStateException("Could not create the " + SystemActor.USER_NO + " user"));
  }
}

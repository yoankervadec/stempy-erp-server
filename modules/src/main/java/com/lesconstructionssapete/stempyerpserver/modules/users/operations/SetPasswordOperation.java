package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository.UserRef;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;

/**
 * Replaces a user's password (already hashed) and ends all their sessions: a
 * password change is often a response to a suspected compromise.
 */
public class SetPasswordOperation implements Operation<SetPasswordOperation.Input, UserRef> {

  public record Input(String userNo, String passwordHash, Instant now) {
    @Override
    public String toString() {
      return "Input[userNo=" + userNo + "]";
    }
  }

  private final UserAccountRepository accounts;
  private final SessionRepository sessions;

  public SetPasswordOperation(UserAccountRepository accounts, SessionRepository sessions) {
    this.accounts = accounts;
    this.sessions = sessions;
  }

  @Override
  public UserRef execute(UnitOfWork uow, Input in) {
    UserRef user = accounts.findByUserNo(uow, in.userNo())
        .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "No user with number " + in.userNo()));
    accounts.replacePassword(uow, user, in.passwordHash(), in.now());
    sessions.revokeAllForUser(uow, user.id(), in.now());
    return user;
  }
}

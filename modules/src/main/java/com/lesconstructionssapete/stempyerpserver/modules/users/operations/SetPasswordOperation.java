package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository.UserRef;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * Replaces a user's password (already hashed) and ends all their sessions: a
 * password change is often a response to a suspected compromise.
 *
 * Audited as PASSWORD_CHANGED (entity "user", install-wide) with NO values:
 * neither the password nor its hash is ever written to the audit log.
 */
public class SetPasswordOperation implements Operation<SetPasswordOperation.Input, UserRef> {

  /**
   * @param actingUserId null when run from the command line
   * @param requestId    null when run from the command line
   */
  public record Input(String userNo, String passwordHash, Long actingUserId, String requestId, Instant now) {
    @Override
    public String toString() {
      return "Input[userNo=" + userNo + "]";
    }
  }

  private final UserAccountRepository accounts;
  private final SessionRepository sessions;
  private final AuditLog audit;

  public SetPasswordOperation(UserAccountRepository accounts, SessionRepository sessions, AuditLog audit) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.audit = audit;
  }

  @Override
  public UserRef execute(UnitOfWork uow, Input in) {
    if (SystemActor.USER_NO.equalsIgnoreCase(in.userNo())) {
      throw ValidationFailedException.field("userNo", "RESERVED_USER",
          "the " + SystemActor.USER_NO + " user cannot log in, so it cannot have a password");
    }
    UserRef user = accounts.findByUserNo(uow, in.userNo())
        .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "No user with number " + in.userNo()));
    accounts.replacePassword(uow, user, in.passwordHash(), in.now());
    sessions.revokeAllForUser(uow, user.id(), in.now());
    audit.record(uow, new AuditEntry("user", user.id(), null, "PASSWORD_CHANGED", in.actingUserId(), in.requestId(),
        in.now(), Map.of()));
    return user;
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionTokens;

/** Logout. Returns true if a live session was ended. */
public class RevokeSessionOperation implements Operation<RevokeSessionOperation.Input, Boolean> {

  public record Input(String token, Instant now) {
    @Override
    public String toString() {
      return "Input[now=" + now + "]";
    }
  }

  private final SessionRepository sessions;

  public RevokeSessionOperation(SessionRepository sessions) {
    this.sessions = sessions;
  }

  @Override
  public Boolean execute(UnitOfWork uow, Input in) {
    return sessions.revoke(uow, SessionTokens.hash(in.token()), in.now()) > 0;
  }
}

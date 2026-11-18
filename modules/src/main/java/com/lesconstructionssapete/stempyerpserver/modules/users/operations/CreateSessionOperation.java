package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository.NewSession;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionTokens;

/**
 * Opens a session for an already-verified user and returns the token, which
 * exists in clear only in this return value. Also drops the user's dead
 * sessions so the table doesn't grow forever.
 */
public class CreateSessionOperation implements Operation<CreateSessionOperation.Input, CreateSessionOperation.Created> {

  public record Input(UserProfile user, Instant now, String clientIp, String userAgent) {
  }

  public record Created(String token, Instant expiresAt) {
    @Override
    public String toString() {
      return "Created[expiresAt=" + expiresAt + "]";
    }
  }

  private final SessionRepository sessions;
  private final AuthSettings settings;

  public CreateSessionOperation(SessionRepository sessions, AuthSettings settings) {
    this.sessions = sessions;
    this.settings = settings;
  }

  @Override
  public Created execute(UnitOfWork uow, Input in) {
    sessions.deleteDeadForUser(uow, in.user().id(), in.now(), in.now().minus(settings.idleTimeout()));

    String token = SessionTokens.newToken();
    Instant expiresAt = in.now().plus(settings.maxLifetime());
    sessions.insert(uow, new NewSession(in.user().companyId(), in.user().id(), SessionTokens.hash(token),
        in.now(), expiresAt, in.clientIp(), in.userAgent()));
    return new Created(token, expiresAt);
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository.ActiveSession;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionTokens;

/**
 * Finds the live session for a token and records that it was used. The
 * last_seen_at write happens at most once a minute per session, not on every
 * request.
 */
public class ResolveSessionOperation implements Operation<ResolveSessionOperation.Input, Optional<ActiveSession>> {

  public record Input(String token, Instant now) {
    @Override
    public String toString() {
      return "Input[now=" + now + "]";
    }
  }

  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

  private final SessionRepository sessions;
  private final AuthSettings settings;

  public ResolveSessionOperation(SessionRepository sessions, AuthSettings settings) {
    this.sessions = sessions;
    this.settings = settings;
  }

  @Override
  public Optional<ActiveSession> execute(UnitOfWork uow, Input in) {
    Optional<ActiveSession> session = sessions.findActive(uow, SessionTokens.hash(in.token()), in.now(),
        in.now().minus(settings.idleTimeout()));
    session.filter(s -> s.lastSeenAt().isBefore(in.now().minus(TOUCH_INTERVAL)))
        .ifPresent(s -> sessions.touch(uow, s.id(), in.now()));
    return session;
  }
}

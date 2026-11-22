package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ResolveSessionOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator;

/**
 * ORCHESTRATOR behind the platform's AuthFilter: one short transaction per
 * authenticated request (an indexed lookup, plus a last_seen_at update at most
 * once a minute).
 */
public class SessionService implements SessionAuthenticator {

  /** Real tokens are 43 characters; don't hash arbitrary junk. */
  private static final int MAX_TOKEN_LENGTH = 128;

  private final UnitOfWorkFactory uowFactory;
  private final ResolveSessionOperation resolveSession;
  private final Clock clock;

  public SessionService(UnitOfWorkFactory uowFactory, ResolveSessionOperation resolveSession, Clock clock) {
    this.uowFactory = uowFactory;
    this.resolveSession = resolveSession;
    this.clock = clock;
  }

  @Override
  public Optional<Principal> authenticate(String token) {
    if (token == null || token.length() > MAX_TOKEN_LENGTH) {
      return Optional.empty();
    }
    var now = clock.instant(); // whole seconds, see UsersModule
    return uowFactory.execute(uow -> resolveSession.execute(uow, new ResolveSessionOperation.Input(token, now)))
        // TODO load the user's permissions once roles/permissions tables exist.
        .map(s -> new Principal(s.userId(), s.companyId(), Set.of()));
  }
}

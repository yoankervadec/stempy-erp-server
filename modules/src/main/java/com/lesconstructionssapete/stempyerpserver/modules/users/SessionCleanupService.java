package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Clock;
import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.PurgeDeadSessionsOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;

/**
 * Session housekeeping, run by the users.purge-expired-sessions job. Login
 * already drops the logging-in user's dead sessions; this catches everyone
 * else's (users who never log in again).
 */
public class SessionCleanupService {

  public static final String JOB_KEY = "users.purge-expired-sessions";
  static final int BATCH = 1000;

  private final UnitOfWorkFactory uowFactory;
  private final AuthSettings settings;
  private final Clock clock;
  private final PurgeDeadSessionsOperation purgeDeadSessions;

  public SessionCleanupService(UnitOfWorkFactory uowFactory, AuthSettings settings, Clock clock,
      PurgeDeadSessionsOperation purgeDeadSessions) {
    this.uowFactory = uowFactory;
    this.settings = settings;
    this.clock = clock;
    this.purgeDeadSessions = purgeDeadSessions;
  }

  /**
   * Deletes dead sessions in batches of {@value #BATCH}, one short transaction
   * each, so logins are never blocked for long.
   *
   * @param checkpoint called before each batch; throws to stop (the job's
   *                   throwIfCancelled)
   * @return how many sessions were deleted
   */
  public int purgeDeadSessions(Runnable checkpoint) {
    Instant now = clock.instant(); // whole seconds, see UsersModule
    var input = new PurgeDeadSessionsOperation.Input(now, now.minus(settings.idleTimeout()), BATCH);
    int total = 0;
    int deleted;
    do {
      checkpoint.run();
      deleted = uowFactory.execute(uow -> purgeDeadSessions.execute(uow, input));
      total += deleted;
    } while (deleted == BATCH);
    return total;
  }
}

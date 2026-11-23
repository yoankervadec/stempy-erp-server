package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.SessionRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Deletes one batch of sessions that can never be used again; returns how many. */
public class PurgeDeadSessionsOperation implements Operation<PurgeDeadSessionsOperation.Input, Integer> {

  /**
   * @param idleCutoff sessions not seen since then are dead (now - idle timeout)
   * @param limit      batch size
   */
  public record Input(Instant now, Instant idleCutoff, int limit) {
  }

  private final SessionRepository sessions;

  public PurgeDeadSessionsOperation(SessionRepository sessions) {
    this.sessions = sessions;
  }

  @Override
  public Integer execute(UnitOfWork uow, Input in) {
    return sessions.deleteDead(uow, in.now(), in.idleCutoff(), in.limit());
  }
}

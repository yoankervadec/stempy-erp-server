package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.PreferenceRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;

/**
 * Creates or replaces one of the user's preferences. A new key is refused
 * once the user has {@code maxKeys} (409 TOO_MANY_PREFERENCES); replacing an
 * existing one always works. The user's row is locked first, so two writes
 * can't both take the last free place. Not audited.
 */
public class PutPreferenceOperation implements Operation<PutPreferenceOperation.Input, Void> {

  /** @param valueJson valid, compact JSON, already checked for size */
  public record Input(long userId, String key, String valueJson, Instant now) {
  }

  private final PreferenceRepository preferences;
  private final int maxKeys;

  public PutPreferenceOperation(PreferenceRepository preferences, int maxKeys) {
    this.preferences = preferences;
    this.maxKeys = maxKeys;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    if (!preferences.lockUser(uow, in.userId())) {
      throw new UnauthenticatedException("SESSION_INVALID", "The session is invalid or has expired");
    }
    if (!preferences.exists(uow, in.userId(), in.key()) && preferences.count(uow, in.userId()) >= maxKeys) {
      throw new ConflictException("TOO_MANY_PREFERENCES",
          "At most " + maxKeys + " preferences per user: delete one before adding another")
          .param("max", maxKeys);
    }
    preferences.upsert(uow, in.userId(), in.key(), in.valueJson(), in.now());
    return null;
  }
}

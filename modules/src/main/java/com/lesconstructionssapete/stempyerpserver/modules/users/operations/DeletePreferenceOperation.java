package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.PreferenceRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Removes one of the user's preferences; a key they don't have is fine. */
public class DeletePreferenceOperation implements Operation<DeletePreferenceOperation.Input, Void> {

  public record Input(long userId, String key) {
  }

  private final PreferenceRepository preferences;

  public DeletePreferenceOperation(PreferenceRepository preferences) {
    this.preferences = preferences;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    preferences.delete(uow, in.userId(), in.key());
    return null;
  }
}

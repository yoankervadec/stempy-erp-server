package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.PreferenceRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.PreferenceRepository.StoredPreference;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Input: the user id. All their preferences, by key. */
public class ListPreferencesOperation implements Operation<Long, List<StoredPreference>> {

  private final PreferenceRepository preferences;

  public ListPreferencesOperation(PreferenceRepository preferences) {
    this.preferences = preferences;
  }

  @Override
  public List<StoredPreference> execute(UnitOfWork uow, Long userId) {
    return preferences.findAll(uow, userId);
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.util.Collection;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.FindUserSummariesOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;

/** UserDirectory for the platform (audit histories), read with the caller's transaction. */
public class UserDirectoryService implements UserDirectory {

  private final FindUserSummariesOperation findSummaries;

  public UserDirectoryService(FindUserSummariesOperation findSummaries) {
    this.findSummaries = findSummaries;
  }

  @Override
  public Map<Long, UserSummary> find(UnitOfWork uow, Collection<Long> ids) {
    return ids.isEmpty() ? Map.of() : findSummaries.execute(uow, ids);
  }
}

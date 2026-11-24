package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory.UserSummary;

/**
 * Id, user number and name of users by id, deleted ones included (an audit
 * history must still name them). Input: the ids; unknown ids are left out.
 */
public class FindUserSummariesOperation implements Operation<Collection<Long>, Map<Long, UserSummary>> {

  private static final int BATCH = 1000;

  private final UserAccountRepository accounts;

  public FindUserSummariesOperation(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Map<Long, UserSummary> execute(UnitOfWork uow, Collection<Long> ids) {
    Map<Long, UserSummary> found = new LinkedHashMap<>();
    List<Long> all = new ArrayList<>(ids);
    for (int from = 0; from < all.size(); from += BATCH) {
      List<Long> batch = all.subList(from, Math.min(all.size(), from + BATCH));
      accounts.findSummaries(uow, batch).forEach(u -> found.put(u.id(), u));
    }
    return found;
  }
}

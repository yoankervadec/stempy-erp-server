package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Input: the user id. */
public class GetUserProfileOperation implements Operation<Long, Optional<UserProfile>> {

  private final UserAccountRepository accounts;

  public GetUserProfileOperation(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<UserProfile> execute(UnitOfWork uow, Long userId) {
    return accounts.findProfile(uow, userId);
  }
}

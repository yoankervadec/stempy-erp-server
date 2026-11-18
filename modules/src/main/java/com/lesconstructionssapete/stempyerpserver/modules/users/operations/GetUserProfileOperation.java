package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

public class GetUserProfileOperation implements Operation<GetUserProfileOperation.Input, Optional<UserProfile>> {

  public record Input(long userId, long companyId) {
  }

  private final UserAccountRepository accounts;

  public GetUserProfileOperation(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<UserProfile> execute(UnitOfWork uow, Input in) {
    return accounts.findProfile(uow, in.userId(), in.companyId());
  }
}

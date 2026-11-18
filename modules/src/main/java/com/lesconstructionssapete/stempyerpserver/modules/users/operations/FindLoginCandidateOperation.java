package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository.LoginCandidate;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** Input: the user number as typed. */
public class FindLoginCandidateOperation implements Operation<String, Optional<LoginCandidate>> {

  private final UserAccountRepository accounts;

  public FindLoginCandidateOperation(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<LoginCandidate> execute(UnitOfWork uow, String userNo) {
    return accounts.findLoginCandidate(uow, userNo);
  }
}

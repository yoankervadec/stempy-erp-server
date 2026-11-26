package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;

/**
 * The language a user reads in a company (docs/ARCHITECTURE.md §23.5): their
 * own choice, else the company's default. A null company: the user's own
 * choice only. Empty when the user (or company) doesn't exist.
 */
public class ResolveLanguageOperation implements Operation<ResolveLanguageOperation.Input, Optional<Language>> {

  public record Input(long userId, Long companyId) {
  }

  private final UserAccountRepository accounts;

  public ResolveLanguageOperation(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<Language> execute(UnitOfWork uow, Input in) {
    return accounts.findLanguages(uow, in.userId(), in.companyId())
        .flatMap(l -> Language.fromCode(l.user()).or(() -> Language.fromCode(l.company())));
  }
}

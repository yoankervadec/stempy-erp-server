package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Sets the language a user chose for themselves (null: follow each company's
 * default). Audited as UPDATED on entity "user" (install-wide), field
 * "language", when it changes.
 */
public class SetLanguageOperation implements Operation<SetLanguageOperation.Input, Void> {

  /** @param language null clears the choice */
  public record Input(long userId, Language language, String requestId, Instant now) {
  }

  private final UserAccountRepository accounts;
  private final AuditLog audit;

  public SetLanguageOperation(UserAccountRepository accounts, AuditLog audit) {
    this.accounts = accounts;
    this.audit = audit;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    String before = accounts.findLanguageForUpdate(uow, in.userId())
        .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "No user " + in.userId())
            .param("user", in.userId()))
        .orElse(null);
    String after = in.language() == null ? null : in.language().code();
    if (Objects.equals(before, after)) {
      return null;
    }
    accounts.setLanguage(uow, in.userId(), after, in.now());
    audit.record(uow, new AuditEntry("user", in.userId(), null, AuditEntry.UPDATED, in.userId(), in.requestId(),
        in.now(), Map.of("language", new FieldChange(before, after))));
    return null;
  }
}

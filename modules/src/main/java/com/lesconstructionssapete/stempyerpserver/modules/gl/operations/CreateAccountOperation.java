package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.Account;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountFields;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountRepository;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;

/**
 * Inserts a validated account; returns its id. The number is unique in the
 * company; the parent, if any, must be a heading of the same company (read
 * with a shared lock, so it can't stop being one before the commit). Audited
 * as CREATED (entity "glAccount").
 */
public class CreateAccountOperation implements Operation<CreateAccountOperation.Input, Long> {

  public record Input(long companyId, AccountFields fields, long userId, String requestId, Instant now) {
  }

  private final AccountRepository repository;
  private final AuditLog audit;

  public CreateAccountOperation(AccountRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    AccountFields f = in.fields();
    var v = Validation.check();
    if (repository.accountNoTaken(uow, in.companyId(), f.accountNo(), 0)) {
      v.add("accountNo", "ALREADY_EXISTS", "another account of the company has this number");
    }
    if (f.parentId() != null) {
      var parent = repository.findForShare(uow, in.companyId(), f.parentId());
      if (parent.isEmpty()) {
        v.add("parentId", "INVALID_REFERENCE", "no account " + f.parentId() + " in this company");
      } else if (!parent.get().fields().isHeading()) {
        v.add("parentId", "NOT_A_HEADING", "the parent must be a heading account");
      }
    }
    v.throwIfInvalid();
    long id = repository.insert(uow, in.companyId(), f, in.userId(), in.now());
    audit.record(uow, new AuditEntry(Account.DEF.name(), id, in.companyId(), AuditEntry.CREATED, in.userId(),
        in.requestId(), in.now(), AccountFields.changes(null, f)));
    return id;
  }
}

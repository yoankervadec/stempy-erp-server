package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.Account;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountFields;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredAccount;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Saves validated changes if nobody changed the account since {@code version}
 * was read (the row is locked first).
 *
 * <ul>
 * <li>the number stays unique in the company;</li>
 * <li>a new parent must be a heading of the same company, and not the account
 * itself or one of its descendants (no cycles);</li>
 * <li>a heading with child accounts stays a heading.</li>
 * </ul>
 *
 * Postings will add their own rule (type and heading frozen once an account
 * has postings) with the journal tables (§22.10 step 3). Audited as UPDATED
 * (entity "glAccount") with the fields that changed; nothing if none did.
 */
public class UpdateAccountOperation implements Operation<UpdateAccountOperation.Input, Void> {

  public record Input(long id, int version, AccountFields fields, long userId, String requestId, Instant now) {
  }

  /** Parent chains longer than this are treated as broken data rather than walked forever. */
  private static final int MAX_DEPTH = 100;

  private final AccountRepository repository;
  private final AuditLog audit;

  public UpdateAccountOperation(AccountRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    StoredAccount current = repository.findForUpdate(uow, in.id())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Account " + in.id() + " not found"));
    if (current.version() != in.version()) {
      throw new ConflictException("VERSION_CONFLICT", "The account was changed by someone else; reload and retry");
    }
    AccountFields before = current.fields();
    AccountFields after = in.fields();
    var v = Validation.check();
    if (!before.accountNo().equalsIgnoreCase(after.accountNo())
        && repository.accountNoTaken(uow, current.companyId(), after.accountNo(), current.id())) {
      v.add("accountNo", "ALREADY_EXISTS", "another account of the company has this number");
    }
    if (after.parentId() != null && !after.parentId().equals(before.parentId())) {
      checkParent(uow, v, current, after.parentId());
    }
    if (before.isHeading() && !after.isHeading() && repository.hasChildren(uow, current.id())) {
      v.add("isHeading", "HAS_CHILDREN", "accounts are grouped under this heading; move them first");
    }
    v.throwIfInvalid();

    if (repository.update(uow, current.id(), in.version(), after, in.userId(), in.now()) == 0) {
      throw new IllegalStateException("account " + current.id() + " changed while locked");
    }
    Map<String, FieldChange> changes = AccountFields.changes(before, after);
    if (!changes.isEmpty()) {
      audit.record(uow, new AuditEntry(Account.DEF.name(), current.id(), current.companyId(), AuditEntry.UPDATED,
          in.userId(), in.requestId(), in.now(), changes));
    }
    return null;
  }

  /** Same company, a heading, and the account is not among its ancestors. */
  private void checkParent(UnitOfWork uow, Validation v, StoredAccount account, long parentId) {
    Optional<StoredAccount> parent = repository.findForShare(uow, parentId)
        .filter(p -> p.companyId() == account.companyId());
    if (parent.isEmpty()) {
      v.add("parentId", "INVALID_REFERENCE", "no account " + parentId + " in this company");
      return;
    }
    if (!parent.get().fields().isHeading()) {
      v.add("parentId", "NOT_A_HEADING", "the parent must be a heading account");
      return;
    }
    Set<Long> seen = new HashSet<>();
    Optional<StoredAccount> cursor = parent;
    while (cursor.isPresent() && seen.size() < MAX_DEPTH) {
      StoredAccount a = cursor.get();
      if (a.id() == account.id()) {
        v.add("parentId", "CYCLE", "an account can't be placed under itself or one of its own sub-accounts");
        return;
      }
      if (!seen.add(a.id()) || a.fields().parentId() == null) {
        return;
      }
      cursor = repository.findForShare(uow, a.fields().parentId());
    }
  }
}

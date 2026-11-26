package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.CreateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.DescribeAccountLocksOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.UpdateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordRules;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Writes to the chart of accounts (orchestrator), docs/ARCHITECTURE.md §22.2.
 * Reads go through the generic entity routes. Accounts of the request's
 * company only; another company's are 404. Needs gl.account.manage.
 *
 * Also the accounts' RecordRules: what an account's history freezes is shown
 * in its "_meta.locked" (accountType and isHeading once it has postings,
 * isHeading while it has sub-accounts), the same rules UpdateAccountOperation
 * enforces.
 */
public class AccountService implements RecordRules {

  public static final String READ = "gl.account.read";
  public static final String MANAGE = "gl.account.manage";

  private final UnitOfWorkFactory uowFactory;
  private final PermissionChecker permissions;
  private final Clock clock;
  private final FindAccountOperation findAccount;
  private final CreateAccountOperation createAccount;
  private final UpdateAccountOperation updateAccount;
  private final DescribeAccountLocksOperation describeLocks;

  public AccountService(UnitOfWorkFactory uowFactory, PermissionChecker permissions, Clock clock,
      FindAccountOperation findAccount, CreateAccountOperation createAccount,
      UpdateAccountOperation updateAccount, DescribeAccountLocksOperation describeLocks) {
    this.uowFactory = uowFactory;
    this.permissions = permissions;
    this.clock = clock;
    this.findAccount = findAccount;
    this.createAccount = createAccount;
    this.updateAccount = updateAccount;
    this.describeLocks = describeLocks;
  }

  @Override
  public Set<String> locked(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
    if (!(row.get("id") instanceof Number id)) {
      return Set.of();
    }
    DescribeAccountLocksOperation.Locks locks = describeLocks.execute(uow, id.longValue());
    Set<String> out = new TreeSet<>();
    if (locks.hasPostings()) {
      out.add(Account.ACCOUNT_TYPE.name());
      out.add(Account.IS_HEADING.name());
    }
    if (locks.hasChildren()) {
      out.add(Account.IS_HEADING.name());
    }
    return out;
  }

  /** Returns the new account's id. */
  public long createAccount(RequestContext rc, JsonNode body) {
    permissions.require(rc, MANAGE);
    AccountForm.Result form = AccountForm.apply(body, null);
    Instant now = clock.instant();
    return uowFactory.execute(uow -> createAccount.execute(uow, new CreateAccountOperation.Input(rc.companyId(),
        form.fields(), rc.userId(), rc.requestId(), now)));
  }

  public void updateAccount(RequestContext rc, long id, JsonNode body) {
    permissions.require(rc, MANAGE);
    StoredAccount current = uowFactory.executeReadOnly(uow -> findAccount.execute(uow, id))
        .filter(a -> a.companyId() == rc.companyId())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Account " + id + " not found"));
    AccountForm.Result form = AccountForm.apply(body, current.fields());
    Instant now = clock.instant();
    uowFactory.run(uow -> updateAccount.execute(uow, new UpdateAccountOperation.Input(id, form.version(),
        form.fields(), rc.userId(), rc.requestId(), now)));
  }
}

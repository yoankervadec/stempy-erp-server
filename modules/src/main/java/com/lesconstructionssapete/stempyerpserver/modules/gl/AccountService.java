package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Clock;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.CreateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.UpdateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Writes to the chart of accounts (orchestrator), docs/ARCHITECTURE.md §22.2.
 * Reads go through the generic entity routes. Accounts of the request's
 * company only; another company's are 404. Needs gl.account.manage.
 */
public class AccountService {

  public static final String READ = "gl.account.read";
  public static final String MANAGE = "gl.account.manage";

  private final UnitOfWorkFactory uowFactory;
  private final PermissionChecker permissions;
  private final Clock clock;
  private final FindAccountOperation findAccount;
  private final CreateAccountOperation createAccount;
  private final UpdateAccountOperation updateAccount;

  public AccountService(UnitOfWorkFactory uowFactory, PermissionChecker permissions, Clock clock,
      FindAccountOperation findAccount, CreateAccountOperation createAccount,
      UpdateAccountOperation updateAccount) {
    this.uowFactory = uowFactory;
    this.permissions = permissions;
    this.clock = clock;
    this.findAccount = findAccount;
    this.createAccount = createAccount;
    this.updateAccount = updateAccount;
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

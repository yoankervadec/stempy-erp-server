package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ChangePeriodStatusOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.CreateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.CreateFiscalYearOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindPeriodOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListPeriodsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.UpdateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * The general ledger (docs/ARCHITECTURE.md §22). This step: the chart of
 * accounts, fiscal years and periods. Dimensions, posting and reports follow
 * (§22.10).
 *
 * Depends on the users module (created_by / updated_by relations), so it must
 * come after it in AllModules.
 */
public class GlModule implements ERPModule {

  private final Clock clock;
  private AccountService accounts; // built in registerServices, used by registerRoutes
  private PeriodService periods;

  public GlModule(Clock clock) {
    // Whole seconds: what a TIMESTAMP column stores.
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofSeconds(1));
  }

  @Override
  public String name() {
    return "gl";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    permissions
        .declare(AccountService.READ, "See the company's chart of accounts")
        .declare(AccountService.MANAGE, "Create and change the company's accounts")
        .declare(PeriodService.READ, "See the company's fiscal years and periods")
        .declare(PeriodService.MANAGE, "Create fiscal years and close periods")
        .declare(PeriodService.REOPEN, "Reopen closed periods, so entries can be posted into them again");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(Account.class);
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
    var uowFactory = serviceRegistry.get(UnitOfWorkFactory.class);
    var permissions = serviceRegistry.get(PermissionChecker.class);
    var audit = serviceRegistry.get(AuditLog.class);
    var accountRepository = new AccountRepository();
    var periodRepository = new PeriodRepository();
    accounts = new AccountService(uowFactory, permissions, clock, new FindAccountOperation(accountRepository),
        new CreateAccountOperation(accountRepository, audit), new UpdateAccountOperation(accountRepository, audit));
    periods = new PeriodService(uowFactory, permissions, clock, new ListPeriodsOperation(periodRepository),
        new FindPeriodOperation(periodRepository), new CreateFiscalYearOperation(periodRepository, audit),
        new ChangePeriodStatusOperation(periodRepository, audit), audit,
        serviceRegistry.find(UserDirectory.class)); // the users module, before this one, publishes it
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    new AccountController(accounts, serviceRegistry.get(EntityEndpoints.class)).register(routes);
    new PeriodController(periods).register(routes);
  }
}

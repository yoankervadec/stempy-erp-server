package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ChangePeriodStatusOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.CreateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.CreateFiscalYearOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.DescribeAccountLocksOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.DescribeDimensionSetOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindJournalEntryOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindOrCreateDimensionSetOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindPeriodOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.InsertJournalEntryOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListDimensionRulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListJournalEntriesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListJournalLinesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.LoadAccountLedgerOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.LoadTrialBalanceOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListPeriodsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.LoadPostingContextOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.MarkReversedOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ReplaceDimensionRulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ResolveDimensionsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.UpdateAccountOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.idempotency.Idempotency;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * The general ledger (docs/ARCHITECTURE.md §22): the chart of accounts, fiscal
 * years and periods, dimensions, journal entries, and the trial balance and
 * account ledger reports. Publishes GeneralLedgerApi.
 *
 * Depends on the users module (created_by / updated_by relations), so it must
 * come after it in AllModules.
 */
public class GlModule implements ERPModule {

  private final Clock clock;
  private AccountService accounts; // built in registerServices, used by registerRoutes
  private PeriodService periods;
  private DimensionService dimensions;
  private JournalService journal;
  private ReportService reports;

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
        .declare(PeriodService.REOPEN, "Reopen closed periods, so entries can be posted into them again")
        .declare(DimensionService.READ, "See the company's dimensions and their values")
        .declare(DimensionService.CREATE, "Create dimensions and dimension values")
        .declare(DimensionService.UPDATE, "Rename, enable and disable dimensions and dimension values")
        .declare(JournalService.READ, "See the company's journal entries")
        .declare(JournalService.POST, "Post manual journal entries")
        .declare(JournalService.REVERSE, "Reverse posted journal entries")
        .declare(ReportService.READ, "See the trial balance and account ledgers");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(Account.class, Dimension.class, DimensionValue.class);
  }

  @Override
  public void registerDocumentTypes(DocumentTypes documentTypes) {
    documentTypes.register(DocumentType.of(JournalPostingService.DOCUMENT_TYPE, "Journal entries")
        .gaplessRequired()
        .withDefaultSeries("JE", "JE{SEQ:8}", Mode.GAPLESS));
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
    var uowFactory = serviceRegistry.get(UnitOfWorkFactory.class);
    var permissions = serviceRegistry.get(PermissionChecker.class);
    var audit = serviceRegistry.get(AuditLog.class);
    var accountRepository = new AccountRepository();
    var periodRepository = new PeriodRepository();
    var dimensionRepository = new DimensionRepository();
    accounts = new AccountService(uowFactory, permissions, clock, new FindAccountOperation(accountRepository),
        new CreateAccountOperation(accountRepository, audit), new UpdateAccountOperation(accountRepository, audit),
        new DescribeAccountLocksOperation(accountRepository));
    periods = new PeriodService(uowFactory, permissions, clock, new ListPeriodsOperation(periodRepository),
        new FindPeriodOperation(periodRepository), new CreateFiscalYearOperation(periodRepository, audit),
        new ChangePeriodStatusOperation(periodRepository, audit), audit,
        serviceRegistry.find(UserDirectory.class)); // the users module, before this one, publishes it
    dimensions = new DimensionService(uowFactory, permissions, clock, new FindAccountOperation(accountRepository),
        new ListDimensionRulesOperation(dimensionRepository),
        new ReplaceDimensionRulesOperation(accountRepository, dimensionRepository, audit),
        new ResolveDimensionsOperation(dimensionRepository), new FindOrCreateDimensionSetOperation(dimensionRepository),
        new DescribeDimensionSetOperation(dimensionRepository));

    var journalRepository = new JournalRepository();
    var posting = new JournalPostingService(dimensions, serviceRegistry.get(DocumentNumbers.class),
        serviceRegistry.get(EventBus.class), clock,
        new LoadPostingContextOperation(journalRepository, periodRepository, accountRepository, dimensionRepository),
        new InsertJournalEntryOperation(journalRepository, audit), new FindJournalEntryOperation(journalRepository),
        new ListJournalLinesOperation(journalRepository), new MarkReversedOperation(journalRepository, audit));
    serviceRegistry.register(GeneralLedgerApi.class, posting);
    journal = new JournalService(uowFactory, permissions, posting, dimensions,
        serviceRegistry.get(Idempotency.class), new FindJournalEntryOperation(journalRepository),
        new ListJournalLinesOperation(journalRepository), new ListJournalEntriesOperation(journalRepository), audit,
        serviceRegistry.find(UserDirectory.class));
    var reportRepository = new ReportRepository();
    reports = new ReportService(uowFactory, permissions, dimensions, new LoadTrialBalanceOperation(reportRepository),
        new LoadAccountLedgerOperation(reportRepository));
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    new AccountController(accounts, serviceRegistry.get(EntityEndpoints.class)).register(routes);
    new PeriodController(periods).register(routes);
    new DimensionController(dimensions, serviceRegistry.get(EntityEndpoints.class)).register(routes);
    new JournalController(journal).register(routes);
    new ReportController(reports).register(routes);
  }
}

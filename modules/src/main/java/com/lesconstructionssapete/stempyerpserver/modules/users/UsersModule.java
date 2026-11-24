package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.AssignRoleOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.CreateSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.EnsureSystemUserOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.FindLoginCandidateOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.FindUserSummariesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.GetUserProfileOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ListUserCompaniesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.LoadEffectivePermissionsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.PurgeDeadSessionsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ResolveSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.RevokeSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.SetPasswordOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.security.CachedCompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.LoginThrottle;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Users and authentication (credentials, sessions, /auth routes).
 *
 * Publishes:
 * - SessionAuthenticator, used by the platform's AuthFilter on every request
 * - CompanyAccess (cached), used by the AuthFilter to pick the request's
 * company and load the user's permissions in it
 * - UsersApi, the module's public API (set-password, assign-role)
 * - SystemActor, the SYSTEM user scheduled work runs as (created on first use)
 *
 * Jobs: users.purge-expired-sessions (hourly by default).
 *
 * Most other entities point at User through created_by / updated_by, so it
 * comes first in AllModules.
 */
public class UsersModule implements ERPModule {

  private final AuthSettings settings;
  private final PasswordHasher hasher;
  private final Clock clock;

  private AuthService authService; // built in registerServices, used by registerRoutes
  private SessionCleanupService sessionCleanup; // built in registerServices, used by registerJobs

  /** Tests pass a cheap hasher so seeding and logging in stay fast. */
  public UsersModule(AuthSettings settings, PasswordHasher hasher, Clock clock) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    // Whole seconds everywhere: what a MySQL TIMESTAMP column stores (it rounds
    // otherwise), so a time we compute equals the time we read back.
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofSeconds(1));
  }

  @Override
  public String name() {
    return "users";
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(User.class);
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
    UnitOfWorkFactory uowFactory = serviceRegistry.get(UnitOfWorkFactory.class);
    PermissionRegistry permissions = serviceRegistry.get(PermissionRegistry.class);
    AuditLog audit = serviceRegistry.get(AuditLog.class);
    var accounts = new UserAccountRepository();
    var sessions = new SessionRepository();
    var access = new AccessRepository();
    var listCompanies = new ListUserCompaniesOperation(access);

    authService = new AuthService(uowFactory, settings, hasher, new CredentialVerifier(hasher),
        new LoginThrottle(settings.maxFailedLogins(), settings.lockout(), clock), clock,
        new FindLoginCandidateOperation(accounts),
        new CreateSessionOperation(sessions, settings),
        new RevokeSessionOperation(sessions),
        new SetPasswordOperation(accounts, sessions, audit),
        new GetUserProfileOperation(accounts),
        listCompanies);

    var companyAccess = new CachedCompanyAccess(
        new CompanyAccessService(uowFactory, new LoadEffectivePermissionsOperation(access, permissions), listCompanies),
        settings.permissionCacheTtl(), clock);
    var authorization = new AuthorizationService(uowFactory, companyAccess, clock,
        new AssignRoleOperation(accounts, access, audit));

    serviceRegistry.register(SessionAuthenticator.class,
        new SessionService(uowFactory, new ResolveSessionOperation(sessions, settings), clock));
    serviceRegistry.register(CompanyAccess.class, companyAccess);
    serviceRegistry.register(UsersApi.class, new UsersApiService(authService, authorization));
    sessionCleanup = new SessionCleanupService(uowFactory, settings, clock,
        new PurgeDeadSessionsOperation(sessions));

    serviceRegistry.register(UserDirectory.class,
        new UserDirectoryService(new FindUserSummariesOperation(accounts)));
    serviceRegistry.register(SystemActor.class,
        new SystemUserService(uowFactory, new EnsureSystemUserOperation(accounts)));
  }

  @Override
  public void registerJobs(Scheduler scheduler, ServiceRegistry serviceRegistry) {
    scheduler.register(JobType.of(SessionCleanupService.JOB_KEY,
        "Deletes sessions that can no longer be used (logged out, expired or idle)", ctx -> {
          int deleted = sessionCleanup.purgeDeadSessions(ctx::throwIfCancelled);
          ctx.setResultSummary(deleted + " session(s) deleted");
        })
        .withTimeout(Duration.ofMinutes(10))
        .withScope(JobType.Scope.SYSTEM)
        .withDefaultSchedule(DefaultSchedule.cron("Purge dead sessions", "15 * * * *", "America/Toronto")));
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    new AuthController(authService).register(routes);
  }
}

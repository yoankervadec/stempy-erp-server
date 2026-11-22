package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.CreateSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.FindLoginCandidateOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.GetUserProfileOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ResolveSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.RevokeSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.SetPasswordOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.LoginThrottle;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Users and authentication (credentials, sessions, /auth routes).
 *
 * Publishes:
 * - SessionAuthenticator, used by the platform's AuthFilter on every request
 * - UsersApi, the module's public API
 *
 * Most other entities point at User through created_by / updated_by, so list
 * this module first in Main.
 */
public class UsersModule implements ERPModule {

  private final AuthSettings settings;
  private final PasswordHasher hasher;
  private final Clock clock;

  private AuthService authService; // built in registerServices, used by registerRoutes

  public UsersModule() {
    this(AuthSettings.defaults());
  }

  public UsersModule(AuthSettings settings) {
    this(settings, new PasswordHasher(), Clock.systemUTC());
  }

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
    var accounts = new UserAccountRepository();
    var sessions = new SessionRepository();

    authService = new AuthService(uowFactory, settings, hasher, new CredentialVerifier(hasher),
        new LoginThrottle(settings.maxFailedLogins(), settings.lockout(), clock), clock,
        new FindLoginCandidateOperation(accounts),
        new CreateSessionOperation(sessions, settings),
        new RevokeSessionOperation(sessions),
        new SetPasswordOperation(accounts, sessions),
        new GetUserProfileOperation(accounts));

    serviceRegistry.register(SessionAuthenticator.class,
        new SessionService(uowFactory, new ResolveSessionOperation(sessions, settings), clock));
    serviceRegistry.register(UsersApi.class, authService);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    new AuthController(authService).register(routes);
  }
}

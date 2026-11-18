package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository.LoginCandidate;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.LoginRequest;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.LoginResponse;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.MeResponse;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.CreateSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.FindLoginCandidateOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.GetUserProfileOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.RevokeSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.SetPasswordOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.LoginThrottle;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * ORCHESTRATOR for login, logout, "who am I" and password changes.
 *
 * Login runs in two short transactions with the slow password check between
 * them, so a burst of logins can't tie up the connection pool for ~0.3s each.
 */
public class AuthService implements UsersApi {

  public static final int MIN_PASSWORD_LENGTH = 8;
  public static final int MAX_PASSWORD_LENGTH = 256;
  private static final int MAX_USER_NO_LENGTH = 50; // users.user_no VARCHAR(50)

  private final UnitOfWorkFactory uowFactory;
  private final AuthSettings settings;
  private final PasswordHasher hasher;
  private final CredentialVerifier verifier;
  private final LoginThrottle throttle;
  private final Clock clock;
  private final FindLoginCandidateOperation findLoginCandidate;
  private final CreateSessionOperation createSession;
  private final RevokeSessionOperation revokeSession;
  private final SetPasswordOperation setPassword;
  private final GetUserProfileOperation getProfile;

  public AuthService(UnitOfWorkFactory uowFactory, AuthSettings settings, PasswordHasher hasher,
      CredentialVerifier verifier, LoginThrottle throttle, Clock clock,
      FindLoginCandidateOperation findLoginCandidate, CreateSessionOperation createSession,
      RevokeSessionOperation revokeSession, SetPasswordOperation setPassword, GetUserProfileOperation getProfile) {
    this.uowFactory = uowFactory;
    this.settings = settings;
    this.hasher = hasher;
    this.verifier = verifier;
    this.throttle = throttle;
    this.clock = clock;
    this.findLoginCandidate = findLoginCandidate;
    this.createSession = createSession;
    this.revokeSession = revokeSession;
    this.setPassword = setPassword;
    this.getProfile = getProfile;
  }

  public LoginResponse login(LoginRequest request, String clientIp, String userAgent) {
    validateLogin(request);
    String userNo = request.userNo().trim();
    String throttleKey = userNo.toLowerCase(Locale.ROOT) + "|" + clientIp;
    throttle.checkAllowed(throttleKey);

    UserProfile user;
    char[] password = request.password().toCharArray();
    try {
      Optional<LoginCandidate> candidate = uowFactory.executeReadOnly(uow -> findLoginCandidate.execute(uow, userNo));
      user = verifier.verify(candidate, password); // no connection held here
    } catch (UnauthenticatedException e) {
      throttle.recordFailure(throttleKey);
      throw e;
    } finally {
      PasswordHasher.wipe(password);
    }
    throttle.recordSuccess(throttleKey);

    Instant now = now();
    CreateSessionOperation.Created session = uowFactory.execute(
        uow -> createSession.execute(uow, new CreateSessionOperation.Input(user, now, clientIp, userAgent)));
    return new LoginResponse(session.token(), session.expiresAt(), settings.idleTimeout().toSeconds(), user);
  }

  /** Ends the session; logging out twice is harmless. */
  public void logout(String token) {
    uowFactory.execute(uow -> revokeSession.execute(uow, new RevokeSessionOperation.Input(token, now())));
  }

  public MeResponse profile(RequestContext rc) {
    UserProfile user = uowFactory.executeReadOnly(
        uow -> getProfile.execute(uow, new GetUserProfileOperation.Input(rc.userId(), rc.companyId())))
        .orElseThrow(() -> new UnauthenticatedException("SESSION_INVALID", "The session is invalid or has expired"));
    return new MeResponse(user, rc.permissions());
  }

  @Override
  public void setPassword(String userNo, char[] password) {

    String trimmedUserNo = userNo != null ? userNo.trim() : null;

    try {
      List<FieldError> errors = new ArrayList<>();
      if (trimmedUserNo == null || trimmedUserNo.isBlank()) {
        errors.add(new FieldError("userNo", "REQUIRED", "is required"));
      }
      if (password == null || password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
        errors.add(new FieldError("password", "INVALID_LENGTH",
            "must be " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH + " characters"));
      }
      if (!errors.isEmpty()) {
        throw new ValidationFailedException("Validation failed", errors);
      }
      String hash = hasher.hash(password); // before the transaction, like login
      uowFactory.execute(uow -> setPassword.execute(uow, new SetPasswordOperation.Input(trimmedUserNo, hash, now())));
    } finally {
      PasswordHasher.wipe(password);
    }
  }

  private static void validateLogin(LoginRequest r) {
    List<FieldError> errors = new ArrayList<>();
    if (r.userNo() == null || r.userNo().isBlank()) {
      errors.add(new FieldError("userNo", "REQUIRED", "is required"));
    } else if (r.userNo().length() > MAX_USER_NO_LENGTH) {
      errors.add(new FieldError("userNo", "TOO_LONG", "must be at most " + MAX_USER_NO_LENGTH + " characters"));
    }
    if (r.password() == null || r.password().isEmpty()) {
      errors.add(new FieldError("password", "REQUIRED", "is required"));
    } else if (r.password().length() > MAX_PASSWORD_LENGTH) {
      errors.add(new FieldError("password", "TOO_LONG", "must be at most " + MAX_PASSWORD_LENGTH + " characters"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", errors);
    }
  }

  /**
   * Whole seconds: what a MySQL TIMESTAMP column stores (it rounds otherwise).
   */
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }
}

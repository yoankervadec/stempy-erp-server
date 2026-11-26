package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository.LoginCandidate;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.CompanySummary;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.LoginRequest;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.LoginResponse;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.MeResponse;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.CreateSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.FindLoginCandidateOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.GetUserProfileOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ListUserCompaniesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ListUserCompaniesOperation.UserCompanies;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ResolveLanguageOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.RevokeSessionOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.SetLanguageOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.SetPasswordOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ForbiddenException;
import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.security.CachedCompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.LoginThrottle;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * ORCHESTRATOR for login, logout, "who am I" and password changes.
 *
 * The clock must tick in whole seconds, what a MySQL TIMESTAMP column stores
 * (UsersModule sets that up).
 *
 * Login runs in two short transactions with the slow password check between
 * them, so a burst of logins can't tie up the connection pool for ~0.3s each.
 */
public class AuthService {

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
  private final ListUserCompaniesOperation listCompanies;
  private final ResolveLanguageOperation resolveLanguage;
  private final SetLanguageOperation setLanguage;
  private final CachedCompanyAccess companyAccess;

  public AuthService(UnitOfWorkFactory uowFactory, AuthSettings settings, PasswordHasher hasher,
      CredentialVerifier verifier, LoginThrottle throttle, Clock clock,
      FindLoginCandidateOperation findLoginCandidate, CreateSessionOperation createSession,
      RevokeSessionOperation revokeSession, SetPasswordOperation setPassword, GetUserProfileOperation getProfile,
      ListUserCompaniesOperation listCompanies, ResolveLanguageOperation resolveLanguage,
      SetLanguageOperation setLanguage, CachedCompanyAccess companyAccess) {
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
    this.listCompanies = listCompanies;
    this.resolveLanguage = resolveLanguage;
    this.setLanguage = setLanguage;
    this.companyAccess = companyAccess;
  }

  public LoginResponse login(LoginRequest request, String clientIp, String userAgent) {
    String userNo = trimmed(request.userNo());
    Validation.check()
        .required("userNo", userNo).maxLength("userNo", userNo, MAX_USER_NO_LENGTH)
        .required("password", request.password()).maxLength("password", request.password(), MAX_PASSWORD_LENGTH)
        .throwIfInvalid();
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
    record Started(CreateSessionOperation.Created session, UserCompanies companies, Language language) {
    }
    Started started = uowFactory.execute(uow -> {
      var session = createSession.execute(uow, new CreateSessionOperation.Input(user, now, clientIp, userAgent));
      UserCompanies companies = listCompanies.execute(uow, user.id());
      Language language = resolveLanguage.execute(uow,
          new ResolveLanguageOperation.Input(user.id(), companies.defaultCompanyId())).orElse(Language.DEFAULT);
      return new Started(session, companies, language);
    });
    return new LoginResponse(started.session().token(), started.session().expiresAt(),
        settings.idleTimeout().toSeconds(), user, started.companies().companies(),
        started.companies().defaultCompanyId(), started.language().code());
  }

  /** Ends the session; logging out twice is harmless. */
  public void logout(String token) {
    uowFactory.execute(uow -> revokeSession.execute(uow, new RevokeSessionOperation.Input(token, now())));
  }

  /** The caller, the request's company and their permissions in it. */
  public MeResponse profile(RequestContext rc) {
    record Loaded(Optional<UserProfile> user, UserCompanies companies) {
    }
    Loaded loaded = uowFactory.executeReadOnly(
        uow -> new Loaded(getProfile.execute(uow, rc.userId()), listCompanies.execute(uow, rc.userId())));
    UserProfile user = loaded.user()
        .orElseThrow(() -> new UnauthenticatedException("SESSION_INVALID", "The session is invalid or has expired"));
    CompanySummary company = loaded.companies().companies().stream()
        .filter(c -> c.id() == rc.companyId()).findFirst()
        .orElseThrow(() -> new ForbiddenException("COMPANY_ACCESS_DENIED", "No access to company " + rc.companyId()));
    return new MeResponse(user, company, new TreeSet<>(rc.permissions()), rc.language().code());
  }

  /**
   * PATCH /auth/me {"language": "fr" | "en" | null}: the caller's own
   * language (null: each company's default). Answers like GET /auth/me, in
   * the language now in effect.
   */
  public MeResponse updateProfile(RequestContext rc, JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
    var v = Validation.check();
    Language language = null;
    boolean given = false;
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      if (!e.getKey().equals("language")) {
        v.add(e.getKey(), "UNKNOWN_FIELD", "is not a field you can change here");
        continue;
      }
      given = true;
      JsonNode value = e.getValue();
      if (!value.isNull()) {
        Optional<Language> parsed = value.isTextual() ? Language.fromCode(value.asText()) : Optional.empty();
        if (parsed.isEmpty() || !value.asText().strip().equalsIgnoreCase(parsed.get().code())) {
          v.add("language", "INVALID_VALUE", "must be one of " + languageCodes() + ", or null");
        } else {
          language = parsed.get();
        }
      }
    }
    if (!given) {
      v.add("language", "REQUIRED", "is required (null follows each company's language)");
    }
    v.throwIfInvalid();
    Language chosen = language;
    uowFactory.run(uow -> setLanguage.execute(uow,
        new SetLanguageOperation.Input(rc.userId(), chosen, rc.requestId(), now())));
    companyAccess.invalidateUser(rc.userId());
    Language effective = companyAccess.language(rc.userId(), rc.companyId()).orElse(Language.DEFAULT);
    return profile(new RequestContext(rc.requestId(), rc.userId(), rc.companyId(), rc.permissions(), effective));
  }

  private static String languageCodes() {
    StringBuilder out = new StringBuilder();
    for (Language l : Language.values()) {
      out.append(out.isEmpty() ? "" : ", ").append(l.code());
    }
    return out.toString();
  }

  public void setPassword(String userNo, char[] password) {
    String trimmedUserNo = trimmed(userNo);
    try {
      Validation.check()
          .required("userNo", trimmedUserNo)
          .lengthBetween("password", password, MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH)
          .throwIfInvalid();
      String hash = hasher.hash(password); // before the transaction, like login
      // From the command line: nobody acting, no request.
      uowFactory.execute(uow -> setPassword.execute(uow,
          new SetPasswordOperation.Input(trimmedUserNo, hash, null, null, now())));
    } finally {
      PasswordHasher.wipe(password);
    }
  }

  private static String trimmed(String s) {
    return s == null ? null : s.trim();
  }

  private Instant now() {
    return clock.instant();
  }
}

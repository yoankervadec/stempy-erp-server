package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository.LoginCandidate;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.UserProfile;
import com.lesconstructionssapete.stempyerpserver.platform.error.UnauthenticatedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;

/**
 * Decides whether a login candidate's password is right. Pure: no database, so
 * the slow hash never runs while holding a pooled connection.
 *
 * Every failure (unknown user, wrong password, disabled user, credential or
 * company) gives the same error, and an unknown user still costs one full hash
 * verification. Neither the response nor its timing tells an attacker which
 * user numbers exist.
 */
public class CredentialVerifier {

  private final PasswordHasher hasher;
  /** Verified against when the user doesn't exist, to equalize timing. */
  private final String dummyHash;

  public CredentialVerifier(PasswordHasher hasher) {
    this.hasher = hasher;
    this.dummyHash = hasher.hash("not-a-real-password".toCharArray());
  }

  public UserProfile verify(Optional<LoginCandidate> candidate, char[] password) {
    boolean matches = hasher.verify(password, candidate.map(LoginCandidate::passwordHash).orElse(dummyHash));
    if (candidate.isEmpty() || !matches || !candidate.get().active()) {
      throw invalidCredentials();
    }
    return candidate.get().user();
  }

  public static UnauthenticatedException invalidCredentials() {
    return new UnauthenticatedException("INVALID_CREDENTIALS", "Invalid user number or password");
  }
}

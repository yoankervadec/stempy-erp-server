package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.time.Duration;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;

/**
 * Session and login policy, read from the auth.* settings
 * (application.properties).
 *
 * @param idleTimeout     a session unused for this long ends
 * @param maxLifetime     a session ends this long after login, used or not
 * @param maxFailedLogins failed logins (per user + client IP) before lockout
 * @param lockout         how long the lockout window lasts
 */
public record AuthSettings(Duration idleTimeout, Duration maxLifetime, int maxFailedLogins, Duration lockout) {

  public AuthSettings {
    Objects.requireNonNull(idleTimeout, "idleTimeout");
    Objects.requireNonNull(maxLifetime, "maxLifetime");
    Objects.requireNonNull(lockout, "lockout");
    if (idleTimeout.isNegative() || idleTimeout.isZero() || maxLifetime.compareTo(idleTimeout) < 0) {
      throw new IllegalArgumentException("need 0 < idleTimeout <= maxLifetime");
    }
    if (maxFailedLogins < 1) {
      throw new IllegalArgumentException("maxFailedLogins must be >= 1");
    }
  }

  /** auth.* settings; anything absent keeps its {@link #defaults()} value. */
  public static AuthSettings from(Settings s) {
    AuthSettings d = defaults();
    return new AuthSettings(
        s.getDuration("auth.session.idleTimeout", d.idleTimeout()),
        s.getDuration("auth.session.maxLifetime", d.maxLifetime()),
        s.getInt("auth.login.maxFailures", d.maxFailedLogins()),
        s.getDuration("auth.login.lockout", d.lockout()));
  }

  /** A workday of inactivity, a week at most, 5 failures per 15 minutes. */
  public static AuthSettings defaults() {
    return new AuthSettings(Duration.ofHours(8), Duration.ofDays(7), 5, Duration.ofMinutes(15));
  }
}

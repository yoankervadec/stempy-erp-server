package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.lesconstructionssapete.stempyerpserver.platform.error.RateLimitedException;

/**
 * Slows down password guessing: after {@code maxFailures} failed logins for
 * the same key within {@code window}, further attempts for that key are
 * refused until the window (counted from the first failure) has passed. A
 * successful login clears the key.
 *
 * Callers key on user + client IP, so a colleague mistyping their own password
 * can't lock anyone else out from another machine.
 *
 * In memory: right for a single server, and a restart clearing the counters is
 * acceptable. Move to Redis/the database if the server is ever replicated.
 */
public final class LoginThrottle {

  /** Clean up expired entries once the map grows past this. */
  private static final int PRUNE_THRESHOLD = 10_000;

  private record Window(int failures, Instant start) {
  }

  private final int maxFailures;
  private final Duration window;
  private final Clock clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public LoginThrottle(int maxFailures, Duration window, Clock clock) {
    if (maxFailures < 1) {
      throw new IllegalArgumentException("maxFailures must be >= 1");
    }
    this.maxFailures = maxFailures;
    this.window = Objects.requireNonNull(window, "window");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Throws 429 if this key is currently locked out. */
  public void checkAllowed(String key) {
    Window w = windows.get(key);
    if (w != null && !expired(w) && w.failures() >= maxFailures) {
      throw new RateLimitedException("LOGIN_RATE_LIMITED",
          "Too many failed login attempts. Try again in a few minutes.");
    }
  }

  public void recordFailure(String key) {
    windows.compute(key, (k, w) -> (w == null || expired(w))
        ? new Window(1, clock.instant())
        : new Window(w.failures() + 1, w.start()));
    if (windows.size() > PRUNE_THRESHOLD) {
      windows.values().removeIf(this::expired);
    }
  }

  public void recordSuccess(String key) {
    windows.remove(key);
  }

  private boolean expired(Window w) {
    return !clock.instant().isBefore(w.start().plus(window));
  }
}

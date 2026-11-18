package com.lesconstructionssapete.stempyerpserver.platform.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.error.RateLimitedException;

class LoginThrottleTest {

  /** A clock the test moves by hand. */
  private static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-01-01T08:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final TestClock clock = new TestClock();
  private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), clock);

  private void fail(String key, int times) {
    for (int i = 0; i < times; i++) {
      throttle.checkAllowed(key);
      throttle.recordFailure(key);
    }
  }

  @Test
  void locksAfterMaxFailures() {
    fail("u1|ip", 3);
    assertThrows(RateLimitedException.class, () -> throttle.checkAllowed("u1|ip"));
  }

  @Test
  void otherKeysAreUnaffected() {
    fail("u1|ip", 3);
    assertDoesNotThrow(() -> throttle.checkAllowed("u1|other-ip"));
    assertDoesNotThrow(() -> throttle.checkAllowed("u2|ip"));
  }

  @Test
  void unlocksWhenTheWindowPasses() {
    fail("u1|ip", 3);
    clock.now = clock.now.plus(Duration.ofMinutes(15));
    assertDoesNotThrow(() -> throttle.checkAllowed("u1|ip"));
  }

  @Test
  void successResetsTheCount() {
    fail("u1|ip", 2);
    throttle.recordSuccess("u1|ip");
    fail("u1|ip", 2);
    assertDoesNotThrow(() -> throttle.checkAllowed("u1|ip"));
  }
}

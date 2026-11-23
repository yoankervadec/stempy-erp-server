package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.time.Duration;

/** Exponential backoff for failed runs: base * 2^failures, capped. */
final class RetryPolicy {

  static final Duration MAX_BACKOFF = Duration.ofHours(1);

  private RetryPolicy() {
  }

  /**
   * @param failuresSoFar retry_attempt before this failure (0 for the first one)
   * @param baseSeconds   retry_backoff_seconds
   */
  static Duration backoff(int failuresSoFar, int baseSeconds) {
    if (baseSeconds <= 0) {
      return Duration.ZERO;
    }
    if (failuresSoFar >= 20) {
      return MAX_BACKOFF; // 2^20 * 1 s is already past the cap; avoids overflow
    }
    long seconds = (long) baseSeconds << failuresSoFar;
    return seconds >= MAX_BACKOFF.toSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
  }
}

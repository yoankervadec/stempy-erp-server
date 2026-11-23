package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  @Test
  void doublesFromTheBase() {
    assertEquals(Duration.ofSeconds(60), RetryPolicy.backoff(0, 60));
    assertEquals(Duration.ofSeconds(120), RetryPolicy.backoff(1, 60));
    assertEquals(Duration.ofSeconds(480), RetryPolicy.backoff(3, 60));
  }

  @Test
  void isCappedAtOneHourWithoutOverflow() {
    assertEquals(Duration.ofHours(1), RetryPolicy.backoff(6, 60)); // 3840 s
    assertEquals(Duration.ofHours(1), RetryPolicy.backoff(40, 60));
    assertEquals(Duration.ofHours(1), RetryPolicy.backoff(Integer.MAX_VALUE, Integer.MAX_VALUE));
  }

  @Test
  void zeroBaseRetriesAtOnce() {
    assertEquals(Duration.ZERO, RetryPolicy.backoff(3, 0));
  }
}

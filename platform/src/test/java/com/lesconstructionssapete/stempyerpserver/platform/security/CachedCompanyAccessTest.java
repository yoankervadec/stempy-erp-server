package com.lesconstructionssapete.stempyerpserver.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CachedCompanyAccessTest {

  /** Counts loads; every answer is "member of 1 with a.b.c, default 1". */
  private static final class CountingSource implements CompanyAccess {
    final AtomicInteger loads = new AtomicInteger();

    @Override
    public Optional<Set<String>> permissions(long userId, long companyId) {
      loads.incrementAndGet();
      return companyId == 1 ? Optional.of(Set.of("a.b.c")) : Optional.empty();
    }

    @Override
    public OptionalLong defaultCompany(long userId) {
      loads.incrementAndGet();
      return OptionalLong.of(1);
    }
  }

  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  private final CountingSource source = new CountingSource();
  private final MutableClock clock = new MutableClock();
  private final CachedCompanyAccess cache = new CachedCompanyAccess(source, Duration.ofMinutes(5), clock);

  @Test
  void answersFromMemoryUntilTheTtlPasses() {
    cache.permissions(7, 1);
    cache.permissions(7, 1);
    cache.permissions(7, 2); // a "no access" answer is cached too
    cache.permissions(7, 2);
    assertEquals(2, source.loads.get());

    clock.now = clock.now.plus(Duration.ofMinutes(5));
    cache.permissions(7, 1);
    assertEquals(3, source.loads.get());
  }

  @Test
  void invalidateUserDropsOnlyThatUser() {
    cache.permissions(7, 1);
    cache.defaultCompany(7);
    cache.permissions(8, 1);

    cache.invalidateUser(7);
    cache.permissions(7, 1);
    cache.defaultCompany(7);
    cache.permissions(8, 1);

    assertEquals(5, source.loads.get());
  }

  @Test
  void invalidateAllDropsEverything() {
    cache.permissions(7, 1);
    cache.permissions(8, 1);
    cache.invalidateAll();
    cache.permissions(7, 1);
    cache.permissions(8, 1);
    assertEquals(4, source.loads.get());
  }
}

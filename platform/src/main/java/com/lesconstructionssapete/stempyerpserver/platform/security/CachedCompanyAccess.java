package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps CompanyAccess answers in memory, so an authenticated request doesn't
 * load roles and overrides every time. Single server instance, so in-process
 * invalidation is exact: whatever changes access (role assignment, override,
 * membership) calls invalidateUser / invalidateAll after its transaction
 * commits.
 *
 * The TTL is a safety net for changes the server doesn't see: direct SQL, or a
 * CLI command run in another process.
 */
public final class CachedCompanyAccess implements CompanyAccess {

  private record Key(long userId, long companyId) {
  }

  private record Entry<T>(T value, Instant loadedAt) {
  }

  private final CompanyAccess source;
  private final Duration ttl;
  private final Clock clock;
  private final ConcurrentHashMap<Key, Entry<Optional<Set<String>>>> permissions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Entry<OptionalLong>> defaults = new ConcurrentHashMap<>();

  public CachedCompanyAccess(CompanyAccess source, Duration ttl, Clock clock) {
    this.source = Objects.requireNonNull(source, "source");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be >= 0");
    }
  }

  @Override
  public Optional<Set<String>> permissions(long userId, long companyId) {
    return cached(permissions, new Key(userId, companyId), () -> source.permissions(userId, companyId));
  }

  @Override
  public OptionalLong defaultCompany(long userId) {
    return cached(defaults, userId, () -> source.defaultCompany(userId));
  }

  /** After a change to one user's memberships, roles or overrides. */
  public void invalidateUser(long userId) {
    permissions.keySet().removeIf(k -> k.userId() == userId);
    defaults.remove(userId);
  }

  /** After a change that can affect many users, e.g. a role's permissions. */
  public void invalidateAll() {
    permissions.clear();
    defaults.clear();
  }

  /**
   * Loads inside compute(), which holds the key's lock: an invalidation that
   * arrives mid-load waits, then removes the fresh entry, so a stale answer
   * can't be stored after it.
   */
  private <K, T> T cached(ConcurrentHashMap<K, Entry<T>> map, K key, Supplier<T> loader) {
    Instant now = clock.instant();
    Entry<T> hit = map.get(key);
    if (hit != null && now.isBefore(hit.loadedAt().plus(ttl))) {
      return hit.value();
    }
    return map.compute(key, (k, e) -> e != null && now.isBefore(e.loadedAt().plus(ttl))
        ? e
        : new Entry<>(loader.get(), now)).value();
  }
}

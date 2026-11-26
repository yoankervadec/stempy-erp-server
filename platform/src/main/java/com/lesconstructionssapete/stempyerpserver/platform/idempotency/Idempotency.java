package com.lesconstructionssapete.stempyerpserver.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;

/**
 * Idempotency keys for POSTs that create documents (docs/ARCHITECTURE.md
 * §22.1). Published in the ServiceRegistry by ModuleHost. Use it in the
 * transaction that creates the document:
 *
 * <pre>
 * long id = uowFactory.execute(uow -&gt; {
 *   if (key != null) {
 *     Optional&lt;String&gt; done = idempotency.begin(uow, key, Idempotency.hash(body.toString()));
 *     if (done.isPresent()) {
 *       return Long.parseLong(done.get());        // a retry: what the first request created
 *     }
 *   }
 *   long created = ... create the document ...;
 *   if (key != null) {
 *     idempotency.complete(uow, key, String.valueOf(created));
 *   }
 *   return created;
 * });
 * </pre>
 *
 * The key row commits or rolls back with the document. A concurrent request
 * with the same key waits for the first one: after its commit it replays the
 * result, after its rollback it runs. Keys are kept {@link #RETENTION}.
 */
public final class Idempotency {

  /** How long a key replays its result; the idempotency.prune job deletes older ones. */
  public static final Duration RETENTION = Duration.ofDays(7);

  public static final String KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

  private final IdempotencyRepository repository = new IdempotencyRepository();
  private final Clock clock;

  public Idempotency(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Claims the key in {@code uow}, or finds what it already produced.
   *
   * @param requestHash {@link #hash} of the request body
   * @return empty: go ahead, then call {@link #complete}; present: the result
   *         of the first request with this key, to replay
   * @throws ConflictException {@code IDEMPOTENCY_KEY_REUSED} (409): the key
   *                           was used with a different request body
   */
  public Optional<String> begin(UnitOfWork uow, IdempotencyKey key, String requestHash) {
    if (repository.insertIfAbsent(uow, key, requestHash, clock.instant())) {
      return Optional.empty();
    }
    IdempotencyRepository.Stored stored = repository.find(uow, key)
        .orElseThrow(() -> new IllegalStateException("idempotency key vanished: " + key.scope()));
    if (!stored.requestHash().equals(requestHash)) {
      throw new ConflictException(KEY_REUSED, "This " + IdempotencyKey.HEADER
          + " was already used with a different request; use a new key for a new request");
    }
    if (stored.result() == null) {
      throw new IllegalStateException("idempotency key committed without a result: " + key.scope());
    }
    return Optional.of(stored.result());
  }

  /** Records what the request produced, in the same transaction as begin(). */
  public void complete(UnitOfWork uow, IdempotencyKey key, String result) {
    Objects.requireNonNull(result, "result");
    if (result.length() > 255) {
      throw new IllegalArgumentException("result longer than 255 characters");
    }
    repository.setResult(uow, key, result);
  }

  /** SHA-256 (hex) of a request's canonical text, e.g. its parsed JSON body's toString(). */
  public static String hash(String canonical) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }

  // ---- housekeeping ----

  public static final String PRUNE_JOB = "idempotency.prune";
  static final int BATCH = 1000;

  /** Deletes keys older than RETENTION, in batches. Registered by ModuleHost. */
  public JobType pruneJob(UnitOfWorkFactory uowFactory) {
    return JobType.of(PRUNE_JOB, "Deletes idempotency keys older than " + RETENTION.toDays() + " days", ctx -> {
      Instant cutoff = clock.instant().minus(RETENTION);
      long total = 0;
      int deleted;
      do {
        ctx.throwIfCancelled();
        deleted = uowFactory.execute(uow -> repository.deleteBefore(uow, cutoff, BATCH));
        total += deleted;
      } while (deleted == BATCH);
      ctx.setResultSummary(total + " idempotency key" + (total == 1 ? "" : "s") + " from before " + cutoff
          + " deleted");
    })
        .withTimeout(Duration.ofMinutes(10))
        .withScope(JobType.Scope.SYSTEM)
        .withDefaultSchedule(DefaultSchedule.cron("Prune idempotency keys", "15 4 * * *", "America/Toronto"));
  }
}

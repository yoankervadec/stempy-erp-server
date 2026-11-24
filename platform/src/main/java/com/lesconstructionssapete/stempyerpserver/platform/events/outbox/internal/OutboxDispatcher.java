package com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.events.AsyncSubscription;
import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxDelivery;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxSettings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RetryPolicy;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Delivers the outbox of one server instance. One poll thread
 * ({@code outbox-poll}) loops:
 * <ol>
 * <li><b>reap</b> (at most once per pollInterval): PROCESSING rows whose
 * lease expired go back to PENDING, or DEAD if that was their last
 * attempt;</li>
 * <li><b>claim</b>, in one short transaction, as many available rows as there
 * are idle workers ({@code FOR UPDATE SKIP LOCKED}, so instances never claim
 * the same row), and hand them to the worker pool;</li>
 * <li>sleep until the next pollInterval, or until a worker finishes after a
 * claim that filled every worker (there may be more waiting).</li>
 * </ol>
 * Only rows of subscriptions registered on this instance are claimed or
 * reaped: during a rolling deploy each instance handles what it knows.
 *
 * A claimed row is leased for its timeout plus {@link #LEASE_MARGIN}, with no
 * renewal: handlers are meant to be short (long work belongs in a scheduler
 * job). A watchdog interrupts a handler at its timeout.
 */
public final class OutboxDispatcher {

  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);
  /** Added to the timeout for the lease: time to record the outcome after an interrupt. */
  static final Duration LEASE_MARGIN = Duration.ofSeconds(30);
  /** How long stop() waits, after interrupting, for handlers to record their end. */
  private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(10);
  private static final int REAP_BATCH = 100;

  private final OutboxSettings settings;
  private final Map<String, AsyncSubscription<?>> subscriptions;
  private final List<String> keys;
  private final UnitOfWorkFactory uowFactory;
  private final SystemActor systemActor;
  private final Set<String> allPermissions;
  private final ObjectMapper json;
  private final OutboxRepository outbox = new OutboxRepository();

  private final Thread poller;
  private final ExecutorService workers;
  private final ScheduledExecutorService watchdog;
  private final AtomicInteger busy = new AtomicInteger();
  private final Semaphore wake = new Semaphore(0);
  /** Message id -> the worker thread running it, for interrupting at shutdown. */
  private final Map<Long, Thread> running = new ConcurrentHashMap<>();
  private volatile boolean lastClaimFull;
  private volatile boolean stopping;
  private volatile boolean interrupting;
  private volatile boolean stopped;
  private long nextReapAtNanos = System.nanoTime(); // nanoTime may be negative: never compare with 0

  public OutboxDispatcher(OutboxSettings settings, List<AsyncSubscription<?>> subscriptions,
      UnitOfWorkFactory uowFactory, SystemActor systemActor, Set<String> allPermissions, ObjectMapper json) {
    this.settings = Objects.requireNonNull(settings, "settings");
    Map<String, AsyncSubscription<?>> byKey = new LinkedHashMap<>();
    subscriptions.forEach(s -> byKey.put(s.key(), s));
    this.subscriptions = Map.copyOf(byKey);
    this.keys = List.copyOf(byKey.keySet());
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    this.systemActor = Objects.requireNonNull(systemActor, "systemActor");
    this.allPermissions = Set.copyOf(allPermissions);
    this.json = Objects.requireNonNull(json, "json");
    this.poller = threads("outbox-poll").newThread(this::loop);
    this.workers = Executors.newFixedThreadPool(settings.workerThreads(), threads("outbox-worker"));
    this.watchdog = Executors.newSingleThreadScheduledExecutor(threads("outbox-watchdog"));
  }

  public void start() {
    systemActor.userId(); // creates the SYSTEM user now rather than in the first delivery
    poller.start();
    LOGGER.info("Outbox dispatcher started as {}: {} async subscription(s) {}, {} worker(s), polling every {}",
        settings.instanceId(), keys.size(), keys, settings.workerThreads(), settings.pollInterval());
  }

  public boolean isStopped() {
    return stopped;
  }

  /**
   * Stops claiming, waits shutdownGrace for running handlers, then interrupts
   * them; their rows go back to PENDING without counting the attempt, so
   * another instance (or the next start) delivers them at once.
   */
  public void stop() {
    if (stopping) {
      return;
    }
    stopping = true;
    LOGGER.info("Outbox dispatcher stopping: no new claims; waiting up to {} for {} running handler(s)",
        settings.shutdownGrace(), busy.get());
    wake.release();
    try {
      poller.join(Duration.ofSeconds(10)); // a claim may be mid-transaction
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    workers.shutdown();
    if (!awaitQuietly(workers, settings.shutdownGrace())) {
      LOGGER.warn("Interrupting {} handler(s) still running", busy.get());
      interrupting = true;
      running.values().forEach(Thread::interrupt);
      if (!awaitQuietly(workers, INTERRUPT_GRACE)) {
        LOGGER.error("{} handler(s) ignored the interrupt; their leases will expire and another instance will "
            + "retry them", busy.get());
        workers.shutdownNow();
      }
    }
    watchdog.shutdownNow();
    stopped = true;
    LOGGER.info("Outbox dispatcher stopped");
  }

  // ---- the poll loop ----

  private void loop() {
    while (!stopping) {
      try {
        reapIfDue();
        claimAndDispatch();
      } catch (RuntimeException e) {
        LOGGER.error("Outbox poll failed; retrying at the next poll", e);
      }
      try {
        // A worker finishing after a full claim wakes us early; extra permits mean "look again".
        wake.tryAcquire(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        wake.drainPermits();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void reapIfDue() {
    long nowNanos = System.nanoTime();
    if (nowNanos - nextReapAtNanos < 0) {
      return;
    }
    nextReapAtNanos = nowNanos + settings.pollInterval().toNanos();
    uowFactory.run(uow -> {
      Instant now = outbox.now(uow);
      for (OutboxRow row : outbox.lockExpired(uow, now, REAP_BATCH, keys)) {
        String error = "Lease of " + row.lockedBy() + " expired during attempt " + row.attempt()
            + ": the instance stopped, lost the database, or the handler ignored its timeout";
        if (row.attempt() >= maxAttempts(subscriptions.get(row.subscriptionKey()))) {
          if (outbox.markDead(uow, row.id(), row.lockedBy(), row.attempt(), now, error)) {
            LOGGER.error("Outbox message {} ({}) is DEAD: {}", row.id(), row.subscriptionKey(), error);
          }
        } else if (outbox.markRetry(uow, row.id(), row.lockedBy(), row.attempt(), now.plus(backoff(row.attempt())),
            error)) {
          LOGGER.warn("Outbox message {} ({}): {}; will retry", row.id(), row.subscriptionKey(), error);
        }
      }
    });
  }

  private void claimAndDispatch() {
    int idle = settings.workerThreads() - busy.get();
    if (idle <= 0 || keys.isEmpty() || stopping) {
      return;
    }
    List<Claimed> claims = uowFactory.execute(uow -> {
      Instant now = outbox.now(uow);
      List<Claimed> claimed = new ArrayList<>();
      for (OutboxRow row : outbox.lockAvailable(uow, now, idle, keys)) {
        AsyncSubscription<?> sub = subscriptions.get(row.subscriptionKey());
        outbox.markProcessing(uow, row.id(), settings.instanceId(), now.plus(timeout(sub)).plus(LEASE_MARGIN));
        claimed.add(new Claimed(row, sub, row.attempt() + 1));
      }
      return claimed;
    });
    lastClaimFull = claims.size() == idle;
    for (Claimed claim : claims) {
      busy.incrementAndGet();
      try {
        workers.execute(() -> {
          try {
            deliver(claim);
          } finally {
            busy.decrementAndGet();
            if (lastClaimFull) {
              wake.release();
            }
          }
        });
      } catch (RejectedExecutionException e) {
        // Shutting down: the lease expires and a reaper (here or elsewhere) retries it.
        busy.decrementAndGet();
        LOGGER.warn("Outbox message {} claimed during shutdown; it will be retried when its lease expires",
            claim.row().id());
      }
    }
  }

  // ---- one delivery, on a worker ----

  private record Claimed(OutboxRow row, AsyncSubscription<?> subscription, int attempt) {
  }

  /** The event in a row could not be turned back into an object the subscription accepts. */
  private static final class UnreadableEventException extends Exception {
    UnreadableEventException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private void deliver(Claimed claim) {
    OutboxRow row = claim.row();
    AsyncSubscription<?> sub = claim.subscription();
    int maxAttempts = maxAttempts(sub);
    Duration timeout = timeout(sub);
    Thread self = Thread.currentThread();
    AtomicBoolean timedOut = new AtomicBoolean();
    running.put(row.id(), self);
    ScheduledFuture<?> timer = watchdog.schedule(() -> {
      timedOut.set(true);
      self.interrupt();
    }, timeout.toMillis(), TimeUnit.MILLISECONDS);

    Throwable failure = null;
    boolean unreadable = false;
    long started = System.nanoTime();
    try {
      DomainEvent event = read(row, sub);
      var delivery = new OutboxDelivery(row.id(), row.subscriptionKey(), claim.attempt(), maxAttempts,
          row.companyId(), row.createdAt(), new RequestContext("outbox-" + row.id() + "-" + claim.attempt(),
              systemActor.userId(), row.companyId(), allPermissions));
      sub.deliver(delivery, event);
    } catch (UnreadableEventException e) {
      failure = e;
      unreadable = true;
    } catch (Throwable t) {
      failure = t;
    } finally {
      timer.cancel(false);
      running.remove(row.id());
      Thread.interrupted(); // a late timer or shutdown interrupt must not leak into the next delivery
    }
    long ms = Duration.ofNanos(System.nanoTime() - started).toMillis();

    try {
      record(claim, maxAttempts, timeout, failure, unreadable, timedOut.get(), ms);
    } catch (RuntimeException e) {
      // The lease expires and the reaper retries: the handler may then run twice (at least once).
      LOGGER.error("Could not record the outcome of outbox message {} ({}); it will be retried when its lease "
          + "expires", row.id(), row.subscriptionKey(), e);
    }
  }

  private void record(Claimed claim, int maxAttempts, Duration timeout, Throwable failure, boolean unreadable,
      boolean timedOut, long ms) {
    OutboxRow row = claim.row();
    String me = settings.instanceId();
    int attempt = claim.attempt();
    uowFactory.run(uow -> {
      Instant now = outbox.now(uow);
      if (failure == null) {
        if (outbox.markSucceeded(uow, row.id(), me, attempt, now)) {
          LOGGER.debug("Outbox message {} ({}) delivered on attempt {} in {} ms", row.id(), row.subscriptionKey(),
              attempt, ms);
        } else {
          lostLease(row);
        }
        return;
      }
      if (interrupting && !timedOut) {
        if (outbox.release(uow, row.id(), me, attempt, now)) {
          LOGGER.warn("Outbox message {} ({}) interrupted by shutdown; released for another attempt", row.id(),
              row.subscriptionKey());
        }
        return;
      }
      String message = timedOut ? "Timed out after " + timeout + " (" + describe(failure) + ")" : describe(failure);
      String error = message + "\n" + stackTrace(failure);
      if (unreadable || attempt >= maxAttempts) {
        if (outbox.markDead(uow, row.id(), me, attempt, now, error)) {
          LOGGER.error("Outbox message {} ({}) is DEAD after attempt {}/{}: {}", row.id(), row.subscriptionKey(),
              attempt, maxAttempts, message, failure);
        } else {
          lostLease(row);
        }
        return;
      }
      Instant retryAt = now.plus(backoff(attempt));
      if (outbox.markRetry(uow, row.id(), me, attempt, retryAt, error)) {
        LOGGER.warn("Outbox message {} ({}) failed attempt {}/{}, retry at {}: {}", row.id(), row.subscriptionKey(),
            attempt, maxAttempts, retryAt, message);
      } else {
        lostLease(row);
      }
    });
  }

  private static void lostLease(OutboxRow row) {
    LOGGER.warn("Outbox message {} ({}): lease lost before the outcome was recorded; another attempt took over",
        row.id(), row.subscriptionKey());
  }

  private DomainEvent read(OutboxRow row, AsyncSubscription<?> sub) throws UnreadableEventException {
    Class<?> type;
    try {
      type = Class.forName(row.eventType(), false, OutboxDispatcher.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      throw new UnreadableEventException("Event class " + row.eventType() + " not found (renamed or moved?)", e);
    }
    if (!sub.type().isAssignableFrom(type)) {
      throw new UnreadableEventException("Event class " + row.eventType() + " is not a "
          + sub.type().getName() + ", which subscription " + sub.key() + " accepts", null);
    }
    try {
      return (DomainEvent) json.readValue(row.payload(), type);
    } catch (Exception e) {
      throw new UnreadableEventException("Payload cannot be read as " + row.eventType() + " (event changed?)", e);
    }
  }

  // ---- helpers ----

  private int maxAttempts(AsyncSubscription<?> sub) {
    return sub != null && sub.maxAttempts() != null ? sub.maxAttempts() : settings.defaultMaxAttempts();
  }

  private Duration timeout(AsyncSubscription<?> sub) {
    return sub != null && sub.timeout() != null ? sub.timeout() : settings.defaultTimeout();
  }

  /** After failed attempt n: retryBackoff * 2^(n-1), capped at 1 hour. */
  private Duration backoff(int attempt) {
    return RetryPolicy.backoff(attempt - 1, (int) Math.min(Integer.MAX_VALUE, settings.retryBackoff().toSeconds()));
  }

  private static String describe(Throwable t) {
    String message = t.getMessage();
    return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  private static String stackTrace(Throwable t) {
    var out = new StringWriter();
    t.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  private static ThreadFactory threads(String prefix) {
    var counter = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
      t.setDaemon(true); // never keep the JVM alive; stop() is what ends deliveries cleanly
      return t;
    };
  }

  private static boolean awaitQuietly(ExecutorService executor, Duration timeout) {
    try {
      return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}

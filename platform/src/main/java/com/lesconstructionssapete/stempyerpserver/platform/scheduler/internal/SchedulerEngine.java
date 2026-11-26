package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.SchedulerSettings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository.NewRun;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository.QueuedRun;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleSpec;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * The running scheduler of one server instance. Every poll, on one thread:
 * <ol>
 * <li><b>recompute</b>: rows whose timing columns changed (or were just
 * enabled / disabled) get a new next_run_at;</li>
 * <li><b>reap</b>: rows whose lease expired (their instance died) get their
 * RUNNING runs marked ABANDONED, and a retry if allowed;</li>
 * <li><b>claim</b>: in one short transaction, lock due rows and queued manual
 * runs (skipping rows other instances hold), lease them, move next_run_at on,
 * and insert RUNNING run rows; then hand them to the worker pool.</li>
 * </ol>
 * The claim takes at most as many runs as there are idle workers, so nothing
 * waits in memory: what isn't claimed stays in the database for the next poll
 * or another instance.
 */
public final class SchedulerEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerEngine.class);
  /** How long stop() waits, after interrupting, for jobs to record their end. */
  private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(10);

  private final SchedulerSettings settings;
  private final Map<String, JobType> jobTypes;
  private final List<String> jobKeys;
  private final UnitOfWorkFactory uowFactory;
  private final SystemActor systemActor;
  private final ScheduleRepository schedules = new ScheduleRepository();
  private final RunRepository runs = new RunRepository();
  private final RunRecorder recorder;
  private final JobRunner runner;

  private final ScheduledExecutorService poller;
  private final ScheduledExecutorService watchdog;
  private final ExecutorService workers;
  private final AtomicInteger busy = new AtomicInteger();
  private volatile boolean stopping;
  private volatile boolean stopped;

  public SchedulerEngine(SchedulerSettings settings, Map<String, JobType> jobTypes, UnitOfWorkFactory uowFactory,
      SystemActor systemActor, CompanyAccess companyAccess, Set<String> allPermissions) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.jobTypes = Map.copyOf(jobTypes);
    this.jobKeys = List.copyOf(jobTypes.keySet());
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    this.systemActor = Objects.requireNonNull(systemActor, "systemActor");
    this.recorder = new RunRecorder(uowFactory, schedules, runs, settings.instanceId());
    this.poller = Executors.newSingleThreadScheduledExecutor(threads("scheduler-poll"));
    this.watchdog = Executors.newSingleThreadScheduledExecutor(threads("scheduler-watchdog"));
    this.workers = Executors.newFixedThreadPool(settings.workerThreads(), virtualThreads("scheduler-worker"));
    this.runner = new JobRunner(settings, this.jobTypes, uowFactory, schedules, recorder, systemActor,
        Objects.requireNonNull(companyAccess, "companyAccess"), allPermissions, watchdog);
  }

  public void start() {
    systemActor.userId(); // creates the SYSTEM user now rather than in the first run
    seedDefaultSchedules();
    poller.scheduleWithFixedDelay(this::poll, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    LOGGER.info("Scheduler started as {}: {} job type(s), {} worker(s), polling every {}", settings.instanceId(),
        jobTypes.size(), settings.workerThreads(), settings.pollInterval());
  }

  public boolean isStopped() {
    return stopped;
  }

  public void stop() {
    if (stopping) {
      return;
    }
    stopping = true;
    LOGGER.info("Scheduler stopping: no new runs; waiting up to {} for {} running job(s)", settings.shutdownGrace(),
        busy.get());
    poller.shutdown();
    awaitQuietly(poller, Duration.ofSeconds(10)); // a poll may be mid-transaction
    workers.shutdown();
    if (!awaitQuietly(workers, settings.shutdownGrace())) {
      LOGGER.warn("Interrupting {} job(s) still running", busy.get());
      runner.cancelAll();
      if (!awaitQuietly(workers, INTERRUPT_GRACE)) {
        LOGGER.error("{} job(s) ignored the interrupt; their leases will expire and another instance will take over",
            busy.get());
        workers.shutdownNow();
      }
    }
    watchdog.shutdownNow();
    stopped = true;
    LOGGER.info("Scheduler stopped");
  }

  // ---- the poll ----

  private void poll() {
    if (stopping) {
      return;
    }
    try {
      recompute();
      reap();
      claimAndDispatch();
    } catch (RuntimeException e) {
      // Never let an exception escape: it would cancel the periodic task for good.
      LOGGER.error("Scheduler poll failed; retrying at the next poll", e);
    }
  }

  /** Gives rows whose timing columns changed a fresh next_run_at (see Schedules.fingerprint). */
  private void recompute() {
    uowFactory.run(uow -> {
      Instant now = schedules.now(uow);
      for (ScheduleRow row : schedules.findAll(uow)) {
        String fingerprint = Schedules.fingerprint(row);
        if (Objects.equals(fingerprint, row.fingerprint())) {
          continue;
        }
        Instant next = null;
        String problem = null;
        if (fingerprint != null) {
          try {
            next = Schedules.nextFire(Schedules.spec(row), row, now).orElse(null);
          } catch (ValidationFailedException e) {
            problem = "invalid schedule " + e.fieldErrors();
          }
        }
        if (!schedules.updateNextRun(uow, row.id(), row.fingerprint(), fingerprint, next)) {
          continue; // another instance just did it
        }
        if (problem != null) {
          LOGGER.warn("Schedule {} ({}) will not run: {}", row.id(), row.name(), problem);
        } else if (fingerprint == null) {
          LOGGER.info("Schedule {} ({}) is disabled", row.id(), row.name());
        } else {
          LOGGER.info("Schedule {} ({}) next runs at {}{}", row.id(), row.name(), next == null ? "never" : next,
              jobTypes.containsKey(row.jobKey()) ? ""
                  : " (job " + row.jobKey() + " is unknown to this instance; another one must run it)");
        }
      }
    });
  }

  /** Abandons the runs of instances that stopped renewing their leases. */
  private void reap() {
    uowFactory.run(uow -> {
      Instant now = schedules.now(uow);
      for (ScheduleRow row : schedules.lockExpiredLeases(uow, now)) {
        var abandoned = runs.abandonRunning(uow, row.id(), now,
            "Lease of " + row.lockedBy() + " expired: the instance stopped or lost the database");
        RunStatus last = abandoned.isEmpty()
            ? (row.lastStatus() == null ? null : RunStatus.valueOf(row.lastStatus()))
            : RunStatus.ABANDONED;
        schedules.clearLease(uow, row.id(), abandoned.isEmpty() ? row.lastRunAt() : now, last);
        LOGGER.warn("Schedule {} ({}): lease of {} expired, {} run(s) abandoned", row.id(), row.name(),
            row.lockedBy(), abandoned.size());
        if (abandoned.stream().anyMatch(r -> r.trigger() != TriggerType.MANUAL)) {
          recorder.applyRetryRules(uow, row.id(), RunStatus.ABANDONED, now);
        }
      }
    });
  }

  private void claimAndDispatch() {
    int idle = settings.workerThreads() - busy.get();
    if (idle <= 0 || jobKeys.isEmpty() || stopping) {
      return;
    }
    for (Claim claim : claim(idle)) {
      busy.incrementAndGet();
      try {
        workers.execute(() -> {
          try {
            runner.run(claim);
          } finally {
            busy.decrementAndGet();
          }
        });
      } catch (RejectedExecutionException e) {
        // Shutting down: the lease expires and the reaper (here or elsewhere) abandons the run.
        busy.decrementAndGet();
        LOGGER.warn("Run {} claimed during shutdown; it will be abandoned when its lease expires", claim.runId());
      }
    }
  }

  /** One transaction: due fires first, then queued manual runs, at most {@code limit} in all. */
  private List<Claim> claim(int limit) {
    return uowFactory.execute(uow -> {
      Instant now = schedules.now(uow);
      Instant leaseUntil = now.plus(settings.lease());
      String me = settings.instanceId();
      List<Claim> claims = new ArrayList<>();
      Set<Long> leased = new HashSet<>();

      for (ScheduleRow row : schedules.lockDue(uow, now, limit, jobKeys)) {
        ScheduleSpec spec;
        try {
          spec = Schedules.spec(row);
        } catch (ValidationFailedException e) {
          schedules.clearNextRun(uow, row.id()); // edited since the last recompute; the next one reports it
          continue;
        }
        Instant scheduledFor = row.nextRunAt();
        // Fire times missed meanwhile are not replayed: the next one is after now.
        Instant following = Schedules.nextFire(spec, row, now).orElse(null);
        boolean retry = row.retryAttempt() > 0;
        TriggerType trigger = retry ? TriggerType.RETRY : TriggerType.SCHEDULE;
        int attempt = row.retryAttempt() + 1;

        boolean late = !retry
            && Duration.between(scheduledFor, now).toSeconds() > row.misfireThresholdSeconds();
        if (late && row.misfirePolicy() == MisfirePolicy.SKIP) {
          runs.insert(uow, new NewRun(row.id(), row.jobKey(), row.companyId(), trigger, null, null,
              RunStatus.SKIPPED, attempt, scheduledFor, now, null, me));
          schedules.advance(uow, row.id(), following);
          LOGGER.warn("Schedule {} ({}) missed its {} fire and skips it (misfire policy SKIP); next at {}",
              row.id(), row.name(), scheduledFor, following);
          continue;
        }
        if (late) {
          LOGGER.warn("Schedule {} ({}) runs its {} fire late, once (misfire policy FIRE_ONCE)", row.id(),
              row.name(), scheduledFor);
        }
        schedules.lease(uow, row.id(), me, leaseUntil, following);
        long runId = runs.insert(uow, new NewRun(row.id(), row.jobKey(), row.companyId(), trigger, null, null,
            RunStatus.RUNNING, attempt, scheduledFor, now, now, me));
        claims.add(new Claim(runId, row, trigger, attempt, scheduledFor, null, row.parameters()));
        leased.add(row.id());
      }

      int left = limit - claims.size();
      if (left > 0) {
        for (QueuedRun q : runs.lockQueued(uow, now, left, jobKeys)) {
          ScheduleRow row = q.schedule();
          if (!leased.add(row.id())) {
            continue; // one run per schedule at a time; the others wait
          }
          schedules.lease(uow, row.id(), me, leaseUntil);
          runs.markStarted(uow, q.runId(), me, now);
          String parameters = q.parametersOverride() != null ? q.parametersOverride() : row.parameters();
          claims.add(new Claim(q.runId(), row, TriggerType.MANUAL, 1, q.requestedAt(), q.requestedBy(), parameters));
        }
      }
      return claims;
    });
  }

  // ---- startup ----

  /** Inserts each job type's default schedule if no row (even a deleted one) uses its key. */
  private void seedDefaultSchedules() {
    long system = systemActor.userId();
    uowFactory.run(uow -> {
      for (JobType type : jobTypes.values()) {
        DefaultSchedule d = type.defaultSchedule();
        if (d != null && !schedules.existsForJobKey(uow, type.key())) {
          schedules.insertDefault(uow, type.key(), d.name(), d.type(), d.expression(), d.timeZone(), system);
          LOGGER.info("Created default schedule '{}' for job {}", d.name(), type.key());
        }
      }
    });
  }

  // ---- helpers ----

  private static ThreadFactory threads(String prefix) {
    var counter = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
      t.setDaemon(true); // never keep the JVM alive; stop() is what ends runs cleanly
      return t;
    };
  }

  /**
   * Workers are virtual threads, still bounded by the fixed pool (workerThreads).
   * Not for throughput: interrupting a virtual thread blocked in socket I/O
   * closes the socket, so a timeout or shutdown also stops a handler or job
   * stuck in a JDBC call (the call fails and the pool discards the connection).
   * A platform thread blocked in a MySQL read ignores the interrupt.
   */
  private static ThreadFactory virtualThreads(String prefix) {
    return Thread.ofVirtual().name(prefix + "-", 1).factory();
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

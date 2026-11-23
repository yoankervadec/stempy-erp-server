package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.SchedulerEngine;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * Runs recurring jobs. Code says WHAT (job types, registered here by modules
 * in ERPModule.registerJobs); the scheduled_jobs table says WHEN, and is
 * re-read on every poll, so schedules can change while the server runs.
 *
 * Lifecycle, like the other registries:
 * <ol>
 * <li>startup: modules {@link #register} job types; ModuleHost then calls
 * {@link #freeze};</li>
 * <li>server mode only: {@link #start} begins polling (CLI commands never run
 * jobs);</li>
 * <li>shutdown: {@link #stop}, before the connection pool is closed.</li>
 * </ol>
 *
 * Several server instances may share a database: each schedule's fire runs
 * once, on whichever instance claims it (row locks + a renewed lease).
 */
public final class Scheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(Scheduler.class);

  private final Map<String, JobType> pending = new LinkedHashMap<>();
  private volatile Map<String, JobType> jobTypes; // null until frozen
  private SchedulerEngine engine;
  private volatile SchedulerSettings settings; // set by start(); read by running jobs

  /** Registers a job type. Only allowed before freeze(); keys are unique. */
  public synchronized void register(JobType type) {
    Objects.requireNonNull(type, "type");
    if (jobTypes != null) {
      throw new IllegalStateException("Scheduler is frozen; register job types in ERPModule.registerJobs");
    }
    if (pending.putIfAbsent(type.key(), type) != null) {
      throw new IllegalStateException("Job type " + type.key() + " is registered twice");
    }
  }

  /** Closes registration. Idempotent. */
  public void freeze() {
    freeze(permission -> true);
  }

  /**
   * Closes registration, failing on a job type whose permission no module
   * declared (a typo would otherwise lock everyone out of it). Idempotent.
   */
  public synchronized void freeze(Predicate<String> declaredPermission) {
    if (jobTypes == null) {
      for (JobType type : pending.values()) {
        if (type.permission() != null && !declaredPermission.test(type.permission())) {
          throw new IllegalStateException("Job type " + type.key() + " requires permission " + type.permission()
              + ", which no module declares");
        }
      }
      jobTypes = Map.copyOf(pending);
      LOGGER.info("Scheduler frozen with {} job type(s): {}", jobTypes.size(), jobTypes.keySet());
    }
  }

  public Optional<JobType> jobType(String key) {
    return Optional.ofNullable(frozen().get(key));
  }

  public Collection<JobType> jobTypes() {
    return frozen().values();
  }

  /**
   * Starts polling. Inserts missing default schedules first.
   *
   * @param systemActor   who scheduled runs act as
   * @param companyAccess the permissions a manual run's requester has in the
   *                      schedule's company
   * @param permissions   every declared permission: what scheduled runs get
   */
  public synchronized void start(SchedulerSettings settings, UnitOfWorkFactory uowFactory, SystemActor systemActor,
      CompanyAccess companyAccess, PermissionRegistry permissions) {
    if (engine != null) {
      throw new IllegalStateException("Scheduler already started");
    }
    this.settings = Objects.requireNonNull(settings, "settings");
    engine = new SchedulerEngine(settings, frozen(), uowFactory, systemActor, companyAccess, permissions.names());
    engine.start();
  }

  /**
   * The settings it was started with. Not synchronized: jobs call it, and
   * stop() holds the lock while it waits for them.
   */
  public SchedulerSettings settings() {
    SchedulerSettings settings = this.settings;
    if (settings == null) {
      throw new IllegalStateException("Scheduler not started");
    }
    return settings;
  }

  public synchronized boolean isRunning() {
    return engine != null && !engine.isStopped();
  }

  /**
   * Stops claiming, waits up to shutdownGrace for running jobs, then
   * interrupts them; their runs are recorded ABANDONED and their leases
   * released so another instance can retry. Idempotent; a no-op if never
   * started.
   */
  public synchronized void stop() {
    if (engine != null) {
      engine.stop();
    }
  }

  private Map<String, JobType> frozen() {
    Map<String, JobType> types = jobTypes;
    if (types == null) {
      throw new IllegalStateException("Scheduler not frozen yet");
    }
    return types;
  }
}

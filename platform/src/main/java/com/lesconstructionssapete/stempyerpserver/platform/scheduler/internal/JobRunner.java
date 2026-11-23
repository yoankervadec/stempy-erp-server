package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.SchedulerSettings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Runs one claimed job on the calling worker thread: builds its context (who
 * it acts as, its parameters), enforces the timeout, keeps the lease alive,
 * and records the outcome.
 */
final class JobRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobRunner.class);
  private static final String MDC_KEY = "requestId"; // same key as web requests (RequestIdFilter)
  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();

  /** The run could not even start (bad parameters, requester lost access): FAILED. */
  static final class SetupException extends RuntimeException {
    SetupException(String message) {
      super(message);
    }
  }

  private final SchedulerSettings settings;
  private final Map<String, JobType> jobTypes;
  private final UnitOfWorkFactory uowFactory;
  private final ScheduleRepository schedules;
  private final RunRecorder recorder;
  private final SystemActor systemActor;
  private final CompanyAccess companyAccess;
  private final Set<String> allPermissions;
  private final ScheduledExecutorService watchdog;
  private final Set<ActiveRun> active = ConcurrentHashMap.newKeySet();

  JobRunner(SchedulerSettings settings, Map<String, JobType> jobTypes, UnitOfWorkFactory uowFactory,
      ScheduleRepository schedules, RunRecorder recorder, SystemActor systemActor, CompanyAccess companyAccess,
      Set<String> allPermissions, ScheduledExecutorService watchdog) {
    this.settings = settings;
    this.jobTypes = jobTypes;
    this.uowFactory = uowFactory;
    this.schedules = schedules;
    this.recorder = recorder;
    this.systemActor = systemActor;
    this.companyAccess = companyAccess;
    this.allPermissions = Set.copyOf(allPermissions);
    this.watchdog = watchdog;
  }

  /** Cancels every running job (shutdown). */
  void cancelAll() {
    active.forEach(a -> a.cancel(ActiveRun.Reason.SHUTDOWN));
  }

  void run(Claim claim) {
    ScheduleRow s = claim.schedule();
    MDC.put(MDC_KEY, "job-run-" + claim.runId());
    var run = new ActiveRun(Thread.currentThread());
    active.add(run);
    long started = System.nanoTime();
    ScheduledFuture<?> timer = null;
    ScheduledFuture<?> renewer = null;
    RunContext ctx = null;
    Throwable failure = null;
    try {
      JobType type = jobTypes.get(s.jobKey());
      ctx = new RunContext(claim, requestContext(claim), parameters(type, claim), run);

      Duration timeout = s.timeoutSeconds() != null ? Duration.ofSeconds(s.timeoutSeconds())
          : type.timeout() != null ? type.timeout() : settings.defaultTimeout();
      timer = watchdog.schedule(() -> run.cancel(ActiveRun.Reason.TIMEOUT), timeout.toMillis(), TimeUnit.MILLISECONDS);
      long every = Math.max(1, settings.lease().toMillis() / 3);
      renewer = watchdog.scheduleAtFixedRate(() -> renew(claim, run), every, every, TimeUnit.MILLISECONDS);

      LOGGER.info("Starting {} run {} of schedule {} ({}), attempt {}, as user {}", claim.trigger(), claim.runId(),
          s.id(), s.name(), claim.attempt(), ctx.requestContext().userId());
      type.job().execute(ctx);
    } catch (Throwable t) {
      failure = t;
    } finally {
      if (timer != null) {
        timer.cancel(false);
      }
      if (renewer != null) {
        renewer.cancel(false);
      }
      run.finish(); // from here on no interrupt can reach this thread
      active.remove(run);
    }

    try {
      long durationMs = (System.nanoTime() - started) / 1_000_000;
      var outcome = outcome(claim, run, failure, durationMs, ctx == null ? null : ctx.resultSummary());
      recorder.finish(claim, outcome);
    } catch (RuntimeException e) {
      // The lease will expire and the reaper marks the run ABANDONED.
      LOGGER.error("Could not record the end of run {} of schedule {}", claim.runId(), s.id(), e);
    } finally {
      MDC.remove(MDC_KEY);
    }
    if (failure instanceof VirtualMachineError fatal) {
      throw fatal;
    }
  }

  private RunRecorder.Outcome outcome(Claim claim, ActiveRun run, Throwable failure, long durationMs,
      String summary) {
    String name = "run " + claim.runId() + " of schedule " + claim.schedule().id() + " ("
        + claim.schedule().name() + ")";
    if (failure == null) {
      if (run.isCancelled()) {
        LOGGER.warn("{} finished although it {}; it should check isCancelled()", name, run.reason().message);
      }
      LOGGER.info("Finished {} in {} ms", name, durationMs);
      return new RunRecorder.Outcome(RunStatus.SUCCEEDED, durationMs, summary, null, null);
    }
    ActiveRun.Reason reason = run.reason();
    RunStatus status = reason == null ? RunStatus.FAILED
        : reason == ActiveRun.Reason.TIMEOUT ? RunStatus.TIMED_OUT : RunStatus.ABANDONED;
    if (failure instanceof SetupException) {
      LOGGER.warn("{} could not start: {}", name, failure.getMessage());
      return new RunRecorder.Outcome(status, durationMs, summary, failure.getMessage(), null);
    }
    String message = reason != null ? "Job " + reason.message : describe(failure);
    LOGGER.warn("{} ended {} after {} ms: {}", name, status, durationMs, message, failure);
    return new RunRecorder.Outcome(status, durationMs, summary, message, stackTrace(failure));
  }

  /**
   * Scheduled runs act as SYSTEM with every permission. A manual run acts as
   * its requester: in a company, with their permissions there (checked again
   * now: they may have lost access since asking); for a system job, with every
   * permission (the API only lets administrators start those).
   */
  private RequestContext requestContext(Claim claim) {
    String requestId = "job-run-" + claim.runId();
    Long company = claim.schedule().companyId();
    long companyId = company == null ? 0 : company;
    if (claim.trigger() != TriggerType.MANUAL) {
      return new RequestContext(requestId, systemActor.userId(), companyId, allPermissions);
    }
    long user = claim.requestedBy();
    if (company == null) {
      return new RequestContext(requestId, user, 0, allPermissions);
    }
    Set<String> permissions = companyAccess.permissions(user, company)
        .orElseThrow(() -> new SetupException("Requesting user " + user + " can no longer work in company " + company));
    return new RequestContext(requestId, user, companyId, permissions);
  }

  private static JsonNode parameters(JobType type, Claim claim) {
    JsonNode node;
    try {
      node = claim.parameters() == null ? JSON.createObjectNode() : JSON.readTree(claim.parameters());
    } catch (JsonProcessingException e) {
      throw new SetupException("Parameters are not valid JSON: " + e.getOriginalMessage());
    }
    try {
      type.parameters().validate(node);
    } catch (ValidationFailedException e) {
      throw new SetupException("Invalid parameters: " + e.fieldErrors());
    }
    return node;
  }

  /** Extends the lease; cancels the run if it is lost, or can't be renewed before it would expire. */
  private void renew(Claim claim, ActiveRun run) {
    try {
      boolean ours = uowFactory.execute(uow -> schedules.renewLease(uow, claim.schedule().id(),
          settings.instanceId(), schedules.now(uow).plus(settings.lease())));
      if (ours) {
        run.leaseRenewed();
      } else {
        run.cancel(ActiveRun.Reason.LEASE_LOST);
      }
    } catch (RuntimeException e) {
      LOGGER.warn("Could not renew the lease of schedule {} for run {}", claim.schedule().id(), claim.runId(), e);
      // Renewal runs every lease/3: if the next try would come too late, stop now rather
      // than keep running after another instance may have taken the schedule over.
      if (run.nanosSinceLeaseRenewed() > settings.lease().toNanos() * 2 / 3) {
        run.cancel(ActiveRun.Reason.LEASE_LOST);
      }
    }
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
}

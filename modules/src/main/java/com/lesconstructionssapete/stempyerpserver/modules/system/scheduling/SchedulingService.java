package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto.JobTypeView;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto.PreviewView;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto.RunView;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto.ScheduleView;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.CreateScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.DeleteScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.FindRunOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.FindScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.ListRunsOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.ListSchedulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.QueueManualRunOperation;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.operations.UpdateScheduleOperation;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditHistory;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditRecord;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ForbiddenException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleSpec;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * The scheduling admin API's rules (orchestrator).
 *
 * Who sees what:
 * <ul>
 * <li>company schedules and their runs: the request's company only, with
 * scheduler.schedule.read; changes need scheduler.schedule.manage, manual runs
 * scheduler.schedule.run;</li>
 * <li>install-wide (SYSTEM) schedules and their runs: scheduler.system.manage
 * for
 * everything; without it they are invisible (404);</li>
 * <li>creating, changing or running a schedule of a job type that names a
 * permission also needs that permission: scheduled runs act as SYSTEM with
 * every permission, so this is what stops a user from scheduling work they
 * could not do themselves.</li>
 * </ul>
 */
public class SchedulingService {

  public static final String READ = "scheduler.schedule.read";
  public static final String MANAGE = "scheduler.schedule.manage";
  public static final String RUN = "scheduler.schedule.run";
  public static final String SYSTEM = "scheduler.system.manage";

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 500;

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();

  /** limit / offset of a list request. */
  public record Page(int limit, int offset) {
  }

  /** A list and the page it is. */
  public record Listing<T>(List<T> data, Page page) {
  }

  private final UnitOfWorkFactory uowFactory;
  private final Scheduler scheduler;
  private final PermissionChecker permissions;
  private final Clock clock;
  private final ListSchedulesOperation listSchedules;
  private final FindScheduleOperation findSchedule;
  private final CreateScheduleOperation createSchedule;
  private final UpdateScheduleOperation updateSchedule;
  private final DeleteScheduleOperation deleteSchedule;
  private final QueueManualRunOperation queueManualRun;
  private final ListRunsOperation listRuns;
  private final FindRunOperation findRun;
  private final AuditLog audit;
  private final Optional<UserDirectory> userDirectory;

  public SchedulingService(UnitOfWorkFactory uowFactory, Scheduler scheduler, PermissionChecker permissions,
      Clock clock, ListSchedulesOperation listSchedules, FindScheduleOperation findSchedule,
      CreateScheduleOperation createSchedule, UpdateScheduleOperation updateSchedule,
      DeleteScheduleOperation deleteSchedule, QueueManualRunOperation queueManualRun, ListRunsOperation listRuns,
      FindRunOperation findRun, AuditLog audit, Optional<UserDirectory> userDirectory) {
    this.uowFactory = uowFactory;
    this.scheduler = scheduler;
    this.permissions = permissions;
    this.clock = clock;
    this.listSchedules = listSchedules;
    this.findSchedule = findSchedule;
    this.createSchedule = createSchedule;
    this.updateSchedule = updateSchedule;
    this.deleteSchedule = deleteSchedule;
    this.queueManualRun = queueManualRun;
    this.listRuns = listRuns;
    this.findRun = findRun;
    this.audit = audit;
    this.userDirectory = userDirectory;
  }

  // ---- job types ----

  public List<JobTypeView> jobTypes(RequestContext rc) {
    requireAny(rc, READ, SYSTEM);
    return scheduler.jobTypes().stream()
        .sorted(Comparator.comparing(JobType::key))
        .map(t -> new JobTypeView(t.key(), t.description(), t.scope(), t.permission(),
            t.timeout() == null ? null : t.timeout().toSeconds(), t.defaultSchedule()))
        .toList();
  }

  // ---- schedules ----

  /**
   * @param scope null: the company's schedules, plus the system ones if the
   *              caller may see them
   */
  public Listing<ScheduleView> listSchedules(RequestContext rc, ScheduleScope scope, Page page) {
    boolean company;
    boolean system;
    if (null == scope) {
      requireAny(rc, READ, SYSTEM);
      company = permissions.has(rc, READ);
      system = permissions.has(rc, SYSTEM);
    } else
      switch (scope) {
        case COMPANY -> {
          permissions.require(rc, READ);
          company = true;
          system = false;
        }
        case SYSTEM -> {
          permissions.require(rc, SYSTEM);
          company = false;
          system = true;
        }
        default -> {
          requireAny(rc, READ, SYSTEM);
          company = permissions.has(rc, READ);
          system = permissions.has(rc, SYSTEM);
        }
      }
    var input = new ListSchedulesOperation.Input(company ? rc.companyId() : null, system, page.limit(),
        page.offset());
    Instant now = clock.instant();
    List<ScheduleView> rows = uowFactory.executeReadOnly(uow -> listSchedules.execute(uow, input)).stream()
        .map(s -> view(s, now)).toList();
    return new Listing<>(rows, page);
  }

  public ScheduleView getSchedule(RequestContext rc, long id) {
    return view(visibleSchedule(rc, id), clock.instant());
  }

  public ScheduleView createSchedule(RequestContext rc, JsonNode body) {
    Instant now = clock.instant();
    ScheduleForm.Result form = ScheduleForm.apply(body, ScheduleFields.defaults(), true, now);
    Long companyId = form.scope() == ScheduleScope.SYSTEM ? null : rc.companyId();
    permissions.require(rc, companyId == null ? SYSTEM : MANAGE);
    checkJob(rc, form.fields(), companyId);

    long id = uowFactory.execute(uow -> createSchedule.execute(uow,
        new CreateScheduleOperation.Input(companyId, form.fields(), rc.userId(), rc.requestId(), now)));
    return getSchedule(rc, id);
  }

  public ScheduleView updateSchedule(RequestContext rc, long id, JsonNode body) {
    StoredSchedule current = visibleSchedule(rc, id);
    permissions.require(rc, current.isSystem() ? SYSTEM : MANAGE);
    requireJobPermission(rc, current.fields().jobKey()); // may they touch what is there now?
    Instant now = clock.instant();
    ScheduleForm.Result form = ScheduleForm.apply(body, current.fields(), false, now);
    checkJob(rc, form.fields(), current.companyId());

    uowFactory.run(uow -> updateSchedule.execute(uow,
        new UpdateScheduleOperation.Input(current, form.version(), form.fields(), rc.userId(), rc.requestId(),
            now)));
    return getSchedule(rc, id);
  }

  public void deleteSchedule(RequestContext rc, long id, Integer version) {
    if (version == null) {
      throw ValidationFailedException.field("version", "REQUIRED", "send ?version= with the version you last read");
    }
    StoredSchedule current = visibleSchedule(rc, id);
    permissions.require(rc, current.isSystem() ? SYSTEM : MANAGE);
    requireJobPermission(rc, current.fields().jobKey());
    Instant now = clock.instant();
    uowFactory.run(uow -> deleteSchedule.execute(uow,
        new DeleteScheduleOperation.Input(current, version, rc.userId(), rc.requestId(), now)));
  }

  /**
   * The schedule's audit entries, newest first: who created, changed or
   * deleted it, and what changed. Visible to whoever may see the schedule and
   * holds audit.history.read.
   */
  public Listing<Map<String, Object>> scheduleHistory(RequestContext rc, long id, Page page) {
    StoredSchedule s = visibleSchedule(rc, id);
    permissions.require(rc, AuditLog.READ_PERMISSION);
    List<Map<String, Object>> items = uowFactory.executeReadOnly(uow -> {
      List<AuditRecord> records = audit.history(uow, "schedule", id, s.companyId(), page.limit(), page.offset());
      Set<Long> userIds = records.stream().map(AuditRecord::userId).filter(Objects::nonNull)
          .collect(Collectors.toSet());
      Map<Long, UserDirectory.UserSummary> users = userIds.isEmpty() ? Map.of()
          : userDirectory.map(d -> d.find(uow, userIds)).orElse(Map.of());
      return AuditHistory.view(records, users);
    });
    return new Listing<>(items, page);
  }

  /** The next fire times of a saved schedule, from now. */
  public PreviewView previewSchedule(RequestContext rc, long id, int count) {
    StoredSchedule s = visibleSchedule(rc, id);
    ScheduleFields f = s.fields();
    ScheduleSpec spec = ScheduleSpec.of(f.scheduleType(), f.cronExpression(), f.interval(), f.timeZone(),
        f.startAt() != null ? f.startAt() : s.createdAt());
    return preview(spec, f.timeZone(), f.startAt(), f.endAt(), count);
  }

  /** The next fire times of a schedule being edited, before it is saved. */
  public PreviewView previewDraft(RequestContext rc, JsonNode body) {
    requireAny(rc, MANAGE, SYSTEM);
    ScheduleForm.Preview p = ScheduleForm.preview(body, clock.instant());
    return preview(p.spec(), p.timeZone(), p.startAt(), p.endAt(), p.count());
  }

  // ---- runs ----

  /**
   * Queues a manual run acting as the caller (with their permissions in the
   * company, checked again when it starts). The regular schedule is not
   * affected. Works on a disabled schedule too, to try a job before enabling it.
   *
   * @param body null or {"parameters": {...}} to override the schedule's
   */
  public RunView requestRun(RequestContext rc, long scheduleId, JsonNode body) {
    StoredSchedule s = visibleSchedule(rc, scheduleId);
    permissions.require(rc, s.isSystem() ? SYSTEM : RUN);
    JobType type = registeredJob(s.fields().jobKey(), "scheduleId");
    requireJobPermission(rc, type);

    String parameters = null;
    if (body != null) {
      if (!body.isObject()) {
        throw new ValidationFailedException("The request body must be a JSON object");
      }
      var v = Validation.check();
      body.fieldNames().forEachRemaining(f -> {
        if (!f.equals("parameters")) {
          v.add(f, "UNKNOWN_FIELD", "unknown field '" + f + "'");
        }
      });
      JsonNode p = body.get("parameters");
      if (p != null && !p.isNull() && !p.isObject()) {
        v.add("parameters", "INVALID_TYPE", "must be a JSON object or null");
      }
      v.throwIfInvalid();
      if (p != null && !p.isNull()) {
        validateParameters(type, p);
        parameters = p.toString();
      }
    }
    Instant now = clock.instant();
    String finalParameters = parameters;
    long runId = uowFactory.execute(uow -> queueManualRun.execute(uow,
        new QueueManualRunOperation.Input(s, rc.userId(), finalParameters, now)));
    return getRun(rc, runId);
  }

  public Listing<RunView> listRuns(RequestContext rc, Long scheduleId, RunStatus status, Page page) {
    requireAny(rc, READ, SYSTEM);
    boolean company = permissions.has(rc, READ);
    boolean system = permissions.has(rc, SYSTEM);
    var input = new ListRunsOperation.Input(company ? rc.companyId() : null, system, scheduleId, status,
        page.limit(), page.offset());
    List<RunView> rows = uowFactory.executeReadOnly(uow -> listRuns.execute(uow, input)).stream()
        .map(r -> view(rc, r)).toList();
    return new Listing<>(rows, page);
  }

  public RunView getRun(RequestContext rc, long id) {
    StoredRun run = uowFactory.executeReadOnly(uow -> findRun.execute(uow, id))
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Run " + id + " not found"));
    requireVisible(rc, run.companyId(), () -> new ResourceNotFoundException("NOT_FOUND", "Run " + id + " not found"));
    return view(rc, run);
  }

  // ---- rules ----

  private StoredSchedule visibleSchedule(RequestContext rc, long id) {
    StoredSchedule s = uowFactory.executeReadOnly(uow -> findSchedule.execute(uow, id))
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Schedule " + id + " not found"));
    requireVisible(rc, s.companyId(),
        () -> new ResourceNotFoundException("NOT_FOUND", "Schedule " + id + " not found"));
    return s;
  }

  /**
   * Another company's rows, and system rows without scheduler.system.manage, are
   * 404:
   * their existence is not revealed. The caller's own company without
   * scheduler.schedule.read is 403.
   */
  private void requireVisible(RequestContext rc, Long companyId,
      Supplier<ResourceNotFoundException> notFound) {
    if (companyId == null) {
      if (!permissions.has(rc, SYSTEM)) {
        throw notFound.get();
      }
    } else if (companyId != rc.companyId()) {
      throw notFound.get();
    } else {
      permissions.require(rc, READ);
    }
  }

  /**
   * The job must exist here, allow this scope, accept the parameters, and the
   * caller must hold its permission.
   */
  private void checkJob(RequestContext rc, ScheduleFields f, Long companyId) {
    JobType type = registeredJob(f.jobKey(), "jobKey");
    if (!type.allows(companyId)) {
      throw ValidationFailedException.field("scope", "NOT_ALLOWED_FOR_JOB", "job " + type.key() + " can only be "
          + (type.scope() == JobType.Scope.SYSTEM ? "scheduled install-wide (scope SYSTEM)"
              : "scheduled for a company (scope COMPANY)"));
    }
    requireJobPermission(rc, type);
    validateParameters(type, f.parameters() == null ? JSON.createObjectNode() : parse(f.parameters()));
  }

  private JobType registeredJob(String key, String field) {
    return scheduler.jobType(key).orElseThrow(() -> ValidationFailedException.field(field, "UNKNOWN_JOB",
        "no job type '" + key + "' on this server (see GET /scheduler/job-types)"));
  }

  private void requireJobPermission(RequestContext rc, String jobKey) {
    scheduler.jobType(jobKey).ifPresent(type -> requireJobPermission(rc, type));
  }

  private void requireJobPermission(RequestContext rc, JobType type) {
    if (type.permission() != null) {
      permissions.require(rc, type.permission());
    }
  }

  /**
   * The job type's own rules; its field names are reported as they are (e.g.
   * "parameters.days").
   */
  private static void validateParameters(JobType type, JsonNode parameters) {
    type.parameters().validate(parameters);
  }

  private void requireAny(RequestContext rc, String... names) {
    for (String name : names) {
      if (permissions.has(rc, name)) {
        return;
      }
    }
    throw ForbiddenException.missingPermission(names[0]);
  }

  // ---- views ----

  private PreviewView preview(ScheduleSpec spec, String timeZone, Instant startAt, Instant endAt, int count) {
    ZoneId zone = ZoneId.of(timeZone);
    var times = spec.preview(startAt, endAt, clock.instant(), Math.clamp(count, 1, 50)).stream()
        .map(at -> new PreviewView.FireTime(at, at.atZone(zone).toOffsetDateTime().toString()))
        .toList();
    return new PreviewView(timeZone, times);
  }

  private ScheduleView view(StoredSchedule s, Instant now) {
    ScheduleFields f = s.fields();
    String runningOn = s.lockedBy() != null && s.lockedUntil() != null && s.lockedUntil().isAfter(now)
        ? s.lockedBy()
        : null;
    return new ScheduleView(s.id(), s.version(), s.isSystem() ? ScheduleScope.SYSTEM : ScheduleScope.COMPANY,
        f.jobKey(), scheduler.jobType(f.jobKey()).isPresent(), f.name(), f.description(), f.scheduleType(),
        f.cronExpression(), f.interval(), f.timeZone(), parseOrNull(f.parameters()), f.enabled(), f.startAt(),
        f.endAt(), f.misfirePolicy(), f.misfireThresholdSeconds(), f.timeoutSeconds(), f.maxRetries(),
        f.retryBackoffSeconds(), s.nextRunAt(), s.lastRunAt(), s.lastStatus(), s.retryAttempt(), runningOn,
        s.createdAt(), s.createdBy(), s.updatedAt(), s.updatedBy());
  }

  private RunView view(RequestContext rc, StoredRun r) {
    String detail = permissions.has(rc, SYSTEM) ? r.errorDetail() : null; // stack traces are for administrators
    return new RunView(r.id(), r.scheduleId(), r.companyId() == null ? ScheduleScope.SYSTEM : ScheduleScope.COMPANY,
        r.jobKey(), r.trigger(), r.requestedBy(), parseOrNull(r.parametersOverride()), r.status(), r.attempt(),
        r.scheduledFor(), r.createdAt(), r.startedAt(), r.finishedAt(), r.durationMs(), r.instanceId(),
        r.resultSummary(), r.errorMessage(), detail);
  }

  private static JsonNode parseOrNull(String json) {
    return Optional.ofNullable(json).map(SchedulingService::parse).orElse(null);
  }

  /**
   * Stored JSON; text edited by hand that no longer parses is shown as a string
   * rather than failing the request.
   */
  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      return JSON.getNodeFactory().textNode(json);
    }
  }
}

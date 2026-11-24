package com.lesconstructionssapete.stempyerpserver.modules.scheduler;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobContext;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.SchedulerSettings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository.NewRun;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository.RunRow;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.ScheduleRepository;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.ScheduleRow;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.Schedules;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.CronExpression;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * The running scheduler against real MySQL, with test job types: claiming,
 * identity, recompute after SQL edits, retries, timeouts, misfires, crash
 * recovery, manual runs, two instances, shutdown.
 *
 * The scheduler polls in the background for the whole class, so each test
 * works only on the schedules it inserts. The shutdown test runs last.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchedulerEngineMySqlTest {

  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final SchedulerSettings SETTINGS = new SchedulerSettings(true, Duration.ofMillis(100), 4,
      Duration.ofSeconds(3), Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofDays(90), "instance-a");

  /** What a test job saw. */
  record Seen(String jobKey, long scheduleId, long runId, TriggerType trigger, int attempt, OptionalLong companyId,
      OptionalLong requestedBy, RequestContext requestContext, JsonNode parameters) {
  }

  static final ConcurrentLinkedQueue<Seen> SEEN = new ConcurrentLinkedQueue<>();
  /** test.block waits while this is false (or until cancelled). */
  static volatile boolean gateOpen;

  static final List<JobType> JOB_TYPES = List.of(
      JobType.of("test.record", "Records what it saw", ctx -> SEEN.add(seen(ctx))),
      JobType.of("test.fail", "Always fails", ctx -> {
        SEEN.add(seen(ctx));
        throw new IllegalStateException("boom");
      }),
      JobType.of("test.block", "Waits for the gate", ctx -> {
        SEEN.add(seen(ctx));
        while (!gateOpen) {
          ctx.throwIfCancelled();
          Thread.sleep(20); // throws when interrupted
        }
        ctx.setResultSummary("gate opened");
      }),
      JobType.of("test.params", "Needs a numeric 'days'", ctx -> SEEN.add(seen(ctx)))
          .withParameters(p -> {
            if (!p.path("days").isInt()) {
              throw ValidationFailedException.field("parameters.days", "REQUIRED", "must be a whole number");
            }
          }),
      JobType.of("test.default", "Comes with a schedule", ctx -> SEEN.add(seen(ctx)))
          .withDefaultSchedule(DefaultSchedule.cron("Default test schedule", "0 3 * * *", "UTC")));

  /** Registers the test job types, like a real module would. */
  static final class TestJobsModule implements ERPModule {
    @Override
    public String name() {
      return "test-jobs";
    }

    @Override
    public void registerEntities(EntityRegistry entityRegistry) {
    }

    @Override
    public void registerJobs(Scheduler scheduler, ServiceRegistry serviceRegistry) {
      JOB_TYPES.forEach(scheduler::register);
    }
  }

  private static Seen seen(JobContext ctx) {
    return new Seen(ctx.jobKey(), ctx.scheduleId(), ctx.runId(), ctx.trigger(), ctx.attempt(), ctx.companyId(),
        ctx.requestedBy(), ctx.requestContext(), ctx.parameters());
  }

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static long systemUserId;
  private final ScheduleRepository schedules = new ScheduleRepository();
  private final RunRepository runs = new RunRepository();

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(SchedulerEngineMySqlTest.class, AuthSettings.defaults(),
        SchedulerEngineMySqlTest::seed, List.of(new TestJobsModule()));
    uow = new UnitOfWorkFactory(stack.dataSource());
    stack.host().startScheduler(SETTINGS);
    systemUserId = stack.services().get(SystemActor.class).userId();
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close(); // stops the scheduler first
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, description, is_enabled) VALUES (1, 'ACME', 'Acme Inc.', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1),
          (2, 1, 'U2', 'Clerk', 1, 1),
          (3, NULL, 'U3', 'Outsider', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'READER', 'Reader')");
    st.execute("INSERT INTO role_permissions (role_id, permission) VALUES (10, 'masterdata.product.read')");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (2, 1, 10)");
  }

  @BeforeEach
  @SuppressWarnings("unused")
  void closeGate() {
    gateOpen = false;
  }

  // ---- helpers ----

  /**
   * Inserts a schedule already due ({@code lateSeconds} ago), with its
   * fingerprint set so the scheduler takes next_run_at as is.
   * {@code columns} are extra "column = value" SQL assignments.
   */
  private long insertDue(String jobKey, String name, long lateSeconds, String... columns) {
    return uow.execute(u -> {
      long id = Jdbc.insert(u, """
          INSERT INTO scheduled_jobs (job_key, name, schedule_type, cron_expression, time_zone)
          VALUES (?, ?, 'CRON', '*/15 * * * *', 'UTC')""", jobKey, name);
      if (columns.length > 0) {
        Jdbc.update(u, "UPDATE scheduled_jobs SET " + String.join(", ", columns) + " WHERE id = ?", id);
      }
      ScheduleRow row = schedules.findById(u, id).orElseThrow();
      Jdbc.update(u, "UPDATE scheduled_jobs SET schedule_fingerprint = ?, next_run_at = ? WHERE id = ?",
          Schedules.fingerprint(row), schedules.now(u).minusSeconds(lateSeconds), id);
      return id;
    });
  }

  /**
   * As insertDue, but next_run_at is a year away: only manual runs or the reaper
   * will touch it.
   */
  private long insertIdle(String jobKey, String name, String... columns) {
    long id = insertDue(jobKey, name, 0, columns);
    uow.run(u -> Jdbc.update(u,
        "UPDATE scheduled_jobs SET next_run_at = UTC_TIMESTAMP() + INTERVAL 1 YEAR WHERE id = ?", id));
    return id;
  }

  private ScheduleRow row(long id) {
    return uow.executeReadOnly(u -> schedules.findById(u, id)).orElseThrow();
  }

  /** Oldest first. */
  private List<RunRow> runsOf(long scheduleId) {
    return uow.executeReadOnly(u -> runs.findBySchedule(u, scheduleId, 100)).reversed();
  }

  private List<Seen> seenBy(long scheduleId) {
    return SEEN.stream().filter(s -> s.scheduleId() == scheduleId).toList();
  }

  private long queueManual(long scheduleId, long userId, String parametersOverride) {
    return uow.execute(u -> {
      ScheduleRow r = schedules.findById(u, scheduleId).orElseThrow();
      Instant now = schedules.now(u);
      return runs.insert(u, new NewRun(scheduleId, r.jobKey(), r.companyId(), TriggerType.MANUAL, userId,
          parametersOverride, RunStatus.QUEUED, 1, now, now, null, null));
    });
  }

  private static void await(String what, BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("Timed out waiting for: " + what);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
  }

  private void awaitStatuses(long scheduleId, RunStatus... expected) {
    await("runs " + List.of(expected), () -> runsOf(scheduleId).stream().map(RunRow::status).toList()
        .equals(List.of(expected)));
  }

  /** Lets a few polls go by, to show that something does NOT happen. */
  private static void settle() throws InterruptedException {
    Thread.sleep(600);
  }

  // ---- scheduled runs ----

  @Test
  void aDueScheduleRunsOnceAsSystemAndMovesOn() throws Exception {
    long id = insertDue("test.record", "Due once", 1);
    awaitStatuses(id, RunStatus.SUCCEEDED);
    settle();
    assertEquals(1, runsOf(id).size());

    Seen seen = seenBy(id).getFirst();
    assertEquals(TriggerType.SCHEDULE, seen.trigger());
    assertEquals(1, seen.attempt());
    assertEquals(OptionalLong.empty(), seen.companyId());
    assertEquals(OptionalLong.empty(), seen.requestedBy());
    assertEquals(systemUserId, seen.requestContext().userId());
    assertEquals(0, seen.requestContext().companyId());
    assertEquals(stack.services().get(PermissionRegistry.class).names(), seen.requestContext().permissions());
    assertTrue(seen.parameters().isObject() && seen.parameters().isEmpty());

    ScheduleRow r = row(id);
    assertNull(r.lockedBy());
    assertEquals("SUCCEEDED", r.lastStatus());
    assertTrue(r.nextRunAt().isAfter(Instant.now().minusSeconds(5)), "next run moved on: " + r.nextRunAt());
    RunRow run = runsOf(id).getFirst();
    assertEquals("instance-a", run.instanceId());
    assertNotNull(run.durationMs());
  }

  @Test
  void aCompanyScheduleRunsInThatCompanyWithItsParameters() {
    long id = insertDue("test.record", "Company job", 1, "company_id = 1", "parameters = '{\"days\": 7}'");
    awaitStatuses(id, RunStatus.SUCCEEDED);
    Seen seen = seenBy(id).getFirst();
    assertEquals(OptionalLong.of(1), seen.companyId());
    assertEquals(1, seen.requestContext().companyId());
    assertEquals(systemUserId, seen.requestContext().userId());
    assertEquals(7, seen.parameters().path("days").asInt());
  }

  @Test
  void invalidParametersFailTheRunBeforeTheJobStarts() {
    long id = insertDue("test.params", "Bad params", 1, "parameters = '{\"days\": \"seven\"}'");
    awaitStatuses(id, RunStatus.FAILED);
    assertTrue(runsOf(id).getFirst().errorMessage().startsWith("Invalid parameters"),
        runsOf(id).getFirst().errorMessage());
    assertTrue(seenBy(id).isEmpty());
  }

  @Test
  void schedulesOfUnknownJobTypesAreLeftAlone() throws Exception {
    long id = insertDue("other.instance-only", "Not ours", 1);
    Instant due = row(id).nextRunAt();
    settle();
    assertTrue(runsOf(id).isEmpty());
    assertEquals(due, row(id).nextRunAt());
  }

  // ---- edits at runtime ----

  @Test
  void sqlEditsAreRecomputedAndDisablingStopsTheRow() {
    long id = uow.execute(u -> Jdbc.insert(u, """
        INSERT INTO scheduled_jobs (job_key, name, schedule_type, cron_expression, time_zone)
        VALUES ('test.record', 'Edited', 'CRON', '0 0 1 1 *', 'UTC')"""));
    Instant newYear = CronExpression.parse("0 0 1 1 *").nextAfter(Instant.now(), ZoneOffset.UTC).orElseThrow();
    await("first recompute", () -> newYear.equals(row(id).nextRunAt()));

    uow.run(u -> Jdbc.update(u, "UPDATE scheduled_jobs SET cron_expression = '0 12 * * *' WHERE id = ?", id));
    await("recompute after the edit", () -> row(id).nextRunAt() != null
        && row(id).nextRunAt().atZone(ZoneOffset.UTC).getHour() == 12);

    uow.run(u -> Jdbc.update(u, "UPDATE scheduled_jobs SET is_enabled = FALSE WHERE id = ?", id));
    await("disabled", () -> row(id).nextRunAt() == null && row(id).fingerprint() == null);

    uow.run(u -> Jdbc.update(u,
        "UPDATE scheduled_jobs SET cron_expression = '61 * * * *', is_enabled = TRUE WHERE id = ?", id));
    await("invalid: fingerprinted but never due", () -> row(id).fingerprint() != null && row(id).nextRunAt() == null);
    assertTrue(runsOf(id).isEmpty());
  }

  // ---- failures ----

  @Test
  void aFailingJobIsRetriedWithBackoffThenWaitsForItsNextFire() {
    long id = insertDue("test.fail", "Retried", 1, "max_retries = 2", "retry_backoff_seconds = 1");
    awaitStatuses(id, RunStatus.FAILED, RunStatus.FAILED, RunStatus.FAILED);

    List<RunRow> all = runsOf(id);
    assertEquals(List.of(TriggerType.SCHEDULE, TriggerType.RETRY, TriggerType.RETRY),
        all.stream().map(RunRow::trigger).toList());
    assertEquals(List.of(1, 2, 3), all.stream().map(RunRow::attempt).toList());
    // backoff: 1 s, then 2 s (starts are whole seconds apart, at least)
    assertTrue(!all.get(1).startedAt().isBefore(all.get(0).finishedAt().plusSeconds(1)));
    assertTrue(!all.get(2).startedAt().isBefore(all.get(1).finishedAt().plusSeconds(2)));
    assertEquals("IllegalStateException: boom", all.getFirst().errorMessage());
    assertTrue(all.getFirst().errorDetail().contains("SchedulerEngineMySqlTest"));

    await("retry count reset", () -> row(id).retryAttempt() == 0 && row(id).lockedBy() == null);
    assertTrue(row(id).nextRunAt().isAfter(Instant.now()));
  }

  @Test
  void aJobPastItsTimeoutIsInterruptedAndMarkedTimedOut() {
    long id = insertDue("test.block", "Too slow", 1, "timeout_seconds = 1");
    awaitStatuses(id, RunStatus.TIMED_OUT);
    RunRow run = runsOf(id).getFirst();
    assertEquals("Job ran past its timeout", run.errorMessage());
    assertTrue(run.durationMs() >= 1000 && run.durationMs() < 5000, "duration " + run.durationMs());
    assertNull(row(id).lockedBy());
  }

  @Test
  void misfiresAreSkippedOrRunOnceByPolicy() throws Exception {
    long skipped = insertDue("test.record", "Misfire skip", 600, "misfire_policy = 'SKIP'");
    long fired = insertDue("test.record", "Misfire fire once", 600);
    awaitStatuses(skipped, RunStatus.SKIPPED);
    awaitStatuses(fired, RunStatus.SUCCEEDED);
    settle();
    assertTrue(seenBy(skipped).isEmpty());
    assertEquals(1, seenBy(fired).size());
    assertTrue(row(skipped).nextRunAt().isAfter(Instant.now().minusSeconds(5)));
    assertNotNull(runsOf(skipped).getFirst().finishedAt());
  }

  @Test
  void runsOfACrashedInstanceAreAbandonedAndRetried() {
    long id = insertIdle("test.record", "Crashed", "max_retries = 1", "retry_backoff_seconds = 1");
    uow.run(u -> {
      Instant now = schedules.now(u);
      schedules.lease(u, id, "dead-instance", now.minusSeconds(1));
      runs.insert(u, new NewRun(id, "test.record", null, TriggerType.SCHEDULE, null, null, RunStatus.RUNNING, 1,
          now.minusSeconds(120), now.minusSeconds(120), now.minusSeconds(120), "dead-instance"));
    });
    awaitStatuses(id, RunStatus.ABANDONED, RunStatus.SUCCEEDED);
    List<RunRow> all = runsOf(id);
    assertTrue(all.getFirst().errorMessage().contains("dead-instance"));
    assertEquals(TriggerType.RETRY, all.get(1).trigger());
    assertEquals(2, all.get(1).attempt());
    await("retry count reset", () -> row(id).retryAttempt() == 0);
  }

  // ---- manual runs ----

  @Test
  void aManualRunActsAsItsRequesterWithTheirPermissions() {
    long id = insertIdle("test.record", "Manual", "company_id = 1", "parameters = '{\"days\": 1}'");
    Instant next = row(id).nextRunAt();
    long runId = queueManual(id, 2, "{\"days\": 30}");
    awaitStatuses(id, RunStatus.SUCCEEDED);

    Seen seen = seenBy(id).getFirst();
    assertEquals(runId, seen.runId());
    assertEquals(TriggerType.MANUAL, seen.trigger());
    assertEquals(OptionalLong.of(2), seen.requestedBy());
    assertEquals(2, seen.requestContext().userId());
    assertEquals(1, seen.requestContext().companyId());
    assertEquals(Set.of("masterdata.product.read"), seen.requestContext().permissions());
    assertEquals(30, seen.parameters().path("days").asInt()); // the override, not the schedule's

    assertEquals(2L, runsOf(id).getFirst().requestedBy());
    assertEquals(next, row(id).nextRunAt()); // the regular schedule is untouched
  }

  @Test
  void aManualRunFailsIfTheRequesterCannotWorkInTheCompany() {
    long id = insertIdle("test.record", "Outsider", "company_id = 1");
    queueManual(id, 3, null);
    awaitStatuses(id, RunStatus.FAILED);
    assertEquals("Requesting user 3 can no longer work in company 1", runsOf(id).getFirst().errorMessage());
    assertTrue(seenBy(id).isEmpty());
  }

  @Test
  void aManualRunOfASystemJobKeepsTheRequesterId() {
    long id = insertIdle("test.record", "Manual system", "is_enabled = FALSE");
    queueManual(id, 1, null);
    awaitStatuses(id, RunStatus.SUCCEEDED); // a disabled schedule can still be run by hand
    Seen seen = seenBy(id).getFirst();
    assertEquals(1, seen.requestContext().userId());
    assertEquals(0, seen.requestContext().companyId());
  }

  @Test
  void aManualRunWaitsWhileTheScheduleIsRunning() throws Exception {
    long id = insertDue("test.block", "Busy", 1);
    await("scheduled run started", () -> runsOf(id).size() == 1 && runsOf(id).getFirst().status() == RunStatus.RUNNING);
    queueManual(id, 1, null);
    settle();
    assertEquals(List.of(RunStatus.RUNNING, RunStatus.QUEUED), runsOf(id).stream().map(RunRow::status).toList());

    gateOpen = true;
    awaitStatuses(id, RunStatus.SUCCEEDED, RunStatus.SUCCEEDED);
    assertEquals("gate opened", runsOf(id).getFirst().resultSummary());
  }

  // ---- several instances ----

  @Test
  void twoInstancesRunEachFireExactlyOnce() {
    var second = new Scheduler();
    JOB_TYPES.forEach(second::register);
    second.freeze();
    var services = stack.services();
    second.start(SETTINGS.withInstanceId("instance-b"), uow, services.get(SystemActor.class),
        services.get(CompanyAccess.class),
        services.get(PermissionRegistry.class));
    try {
      List<Long> ids = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        ids.add(insertDue("test.record", "Shared " + i, 1));
      }
      await("all 30 ran", () -> ids.stream().allMatch(id -> runsOf(id).stream()
          .anyMatch(r -> r.status() == RunStatus.SUCCEEDED)));
      for (long id : ids) {
        assertEquals(1, runsOf(id).size(), "schedule " + id);
        assertEquals(1, seenBy(id).size(), "schedule " + id);
      }
      Set<String> instances = new HashSet<>();
      ids.forEach(id -> instances.add(runsOf(id).getFirst().instanceId()));
      System.out.println("[SchedulerEngineMySqlTest] 30 fires ran on " + instances);

      // the default schedule was seeded once, by whichever instance started first
      assertEquals(1, uow.executeReadOnly(u -> Jdbc.queryFirst(u,
          "SELECT COUNT(*) FROM scheduled_jobs WHERE job_key = 'test.default'", rs -> rs.getInt(1))).orElseThrow());
      assertEquals(Map.of("created_by", systemUserId), uow.executeReadOnly(u -> Jdbc.queryFirst(u,
          "SELECT created_by FROM scheduled_jobs WHERE job_key = 'test.default'",
          rs -> Map.of("created_by", rs.getLong(1)))).orElseThrow());
    } finally {
      second.stop();
    }
  }

  // ---- shutdown (last: it stops the class's scheduler) ----

  @Test
  @Order(Integer.MAX_VALUE)
  void shutdownInterruptsRunningJobsAndReleasesTheirLeases() {
    long id = insertDue("test.block", "Running at shutdown", 1);
    await("running", () -> runsOf(id).size() == 1 && runsOf(id).getFirst().status() == RunStatus.RUNNING);

    Scheduler scheduler = stack.services().get(Scheduler.class);
    scheduler.stop(); // grace 1 s, then interrupt
    assertFalse(scheduler.isRunning());

    RunRow run = runsOf(id).getFirst();
    assertEquals(RunStatus.ABANDONED, run.status());
    assertEquals("Job interrupted by server shutdown", run.errorMessage());
    assertNull(row(id).lockedBy()); // another instance can take over at once
  }
}

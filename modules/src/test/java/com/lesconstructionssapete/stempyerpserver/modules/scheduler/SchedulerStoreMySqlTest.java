package com.lesconstructionssapete.stempyerpserver.modules.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.users.SystemUserService;
import com.lesconstructionssapete.stempyerpserver.modules.users.UserAccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.EnsureSystemUserOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository.NewRun;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.ScheduleRepository;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.ScheduleRow;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.Schedules;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * The scheduler's tables (migration V5), its repositories against real MySQL
 * (row locking, leases, the duplicate-fire guard) and the SYSTEM user.
 */
class SchedulerStoreMySqlTest {

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private final ScheduleRepository schedules = new ScheduleRepository();
  private final RunRepository runs = new RunRepository();

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(SchedulerStoreMySqlTest.class, AuthSettings.defaults(), SchedulerStoreMySqlTest::seed);
    uow = new UnitOfWorkFactory(stack.dataSource());
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, description, is_enabled) VALUES (1, 'ACME', 'Acme Inc.', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1),
          (2, 1, 'U2', 'Clerk', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1)");
  }

  @BeforeEach
  void emptyTables() {
    uow.run(u -> {
      Jdbc.update(u, "DELETE FROM scheduled_job_runs");
      Jdbc.update(u, "DELETE FROM scheduled_jobs");
    });
  }

  // ---- helpers ----

  /** A due system CRON schedule; returns its id. */
  private long insertDue(String name) {
    return uow.execute(u -> Jdbc.insert(u, """
        INSERT INTO scheduled_jobs (job_key, name, schedule_type, cron_expression, time_zone, next_run_at)
        VALUES ('test.job', ?, 'CRON', '*/15 * * * *', 'UTC', UTC_TIMESTAMP() - INTERVAL 1 MINUTE)""", name));
  }

  /** A transaction returning a boolean, typed so assertTrue / assertFalse pick their boolean overload. */
  private static boolean inTx(Function<UnitOfWork, Boolean> work) {
    return uow.execute(work);
  }

  private ScheduleRow find(long id) {
    return uow.executeReadOnly(u -> schedules.findById(u, id)).orElseThrow();
  }

  private Instant now() {
    return uow.executeReadOnly(schedules::now);
  }

  private static NewRun scheduled(long scheduleId, Instant scheduledFor, int attempt, Instant now) {
    return new NewRun(scheduleId, "test.job", null, attempt == 1 ? TriggerType.SCHEDULE : TriggerType.RETRY, null,
        null, RunStatus.RUNNING, attempt, scheduledFor, now, now, "instance-a");
  }

  private static NewRun manual(long scheduleId, long userId, Instant now) {
    return new NewRun(scheduleId, "test.job", null, TriggerType.MANUAL, userId, "{\"dryRun\": true}",
        RunStatus.QUEUED, 1, now, now, null, null);
  }

  // ---- schema ----

  @Test
  void databaseClockIsUtcAndCloseToOurs() {
    Duration skew = Duration.between(now(), Instant.now()).abs();
    assertTrue(skew.compareTo(Duration.ofSeconds(5)) < 0, "skew " + skew);
  }

  @Test
  void checkConstraintsRejectIncompleteSchedules() {
    assertThrows(DataAccessException.class, () -> uow.run(u -> Jdbc.update(u,
        "INSERT INTO scheduled_jobs (job_key, name, schedule_type) VALUES ('test.job', 'No cron', 'CRON')")));
    assertThrows(DataAccessException.class, () -> uow.run(u -> Jdbc.update(u,
        "INSERT INTO scheduled_jobs (job_key, name, schedule_type) VALUES ('test.job', 'No interval', 'INTERVAL')")));
    assertThrows(DataAccessException.class, () -> uow.run(u -> Jdbc.update(u, """
        INSERT INTO scheduled_jobs (job_key, name, schedule_type, interval_value, start_at, end_at)
        VALUES ('test.job', 'Backwards', 'INTERVAL', 'PT1H', '2026-02-01', '2026-01-01')""")));
  }

  @Test
  void namesAreUniqueAmongSystemJobsAndPerCompanyUntilDeleted() {
    long first = insertDue("Nightly");
    assertThrows(DataAccessException.class, () -> insertDue("Nightly"));
    // the same name in a company is fine
    uow.run(u -> Jdbc.update(u, """
        INSERT INTO scheduled_jobs (company_id, job_key, name, schedule_type, cron_expression)
        VALUES (1, 'test.job', 'Nightly', 'CRON', '0 0 * * *')"""));
    // a soft-deleted schedule frees its name
    uow.run(u -> Jdbc.update(u, "UPDATE scheduled_jobs SET deleted_at = UTC_TIMESTAMP() WHERE id = ?", first));
    insertDue("Nightly");
    assertEquals(2, uow.executeReadOnly(schedules::findAll).size()); // the deleted one is invisible
  }

  // ---- recompute ----

  @Test
  void rowsMapAndRecomputeIsFirstWriterWins() {
    long id = insertDue("Recompute");
    ScheduleRow row = find(id);
    assertEquals("test.job", row.jobKey());
    assertNull(row.companyId());
    assertNull(row.fingerprint());
    assertTrue(row.enabled());

    String fingerprint = Schedules.fingerprint(row);
    Instant next = Schedules.nextFire(Schedules.spec(row), row, now()).orElseThrow();
    assertTrue(inTx(u -> schedules.updateNextRun(u, id, null, fingerprint, next)));
    // a second instance that saw the same old fingerprint changes nothing
    assertFalse(inTx(u -> schedules.updateNextRun(u, id, null, fingerprint, next.plusSeconds(60))));

    ScheduleRow after = find(id);
    assertEquals(fingerprint, after.fingerprint());
    assertEquals(next, after.nextRunAt());
  }

  @Test
  void invalidRowsEditedWithSqlAreLoadedAndReportedNotThrown() {
    long id = insertDue("Broken");
    uow.run(u -> Jdbc.update(u, "UPDATE scheduled_jobs SET cron_expression = '61 * * * *' WHERE id = ?", id));
    ScheduleRow row = find(id);
    assertThrows(ValidationFailedException.class, () -> Schedules.spec(row));
  }

  // ---- claiming ----

  @Test
  void aDueRowIsClaimedByOneTransactionOnly() {
    long id = insertDue("Contended");
    Instant now = now();
    try (UnitOfWork first = uow.begin(); UnitOfWork second = uow.begin()) {
      List<ScheduleRow> a = schedules.lockDue(first, now, 10);
      assertEquals(List.of(id), a.stream().map(ScheduleRow::id).toList());
      // the row is locked by the first transaction: skipped, not waited for
      assertEquals(List.of(), schedules.lockDue(second, now, 10));

      schedules.lease(first, id, "instance-a", now.plusSeconds(60), now.plusSeconds(900));
      first.commit();
      // committed and leased: still not due for anyone else
      assertEquals(List.of(), schedules.lockDue(second, now, 10));
      second.commit();
    }
    ScheduleRow row = find(id);
    assertEquals("instance-a", row.lockedBy());
    assertEquals(now.plusSeconds(900), row.nextRunAt());
  }

  @Test
  void disabledAndNotYetDueRowsAreNotClaimed() {
    long disabled = insertDue("Disabled");
    long future = insertDue("Future");
    uow.run(u -> {
      Jdbc.update(u, "UPDATE scheduled_jobs SET is_enabled = FALSE WHERE id = ?", disabled);
      Jdbc.update(u, "UPDATE scheduled_jobs SET next_run_at = UTC_TIMESTAMP() + INTERVAL 1 HOUR WHERE id = ?", future);
    });
    assertEquals(List.of(), uow.execute(u -> schedules.lockDue(u, schedules.now(u), 10)));
  }

  @Test
  void leaseRenewalAndReleaseOnlyWorkForTheHolder() {
    long id = insertDue("Leased");
    Instant now = now();
    uow.run(u -> schedules.lease(u, id, "instance-a", now.plusSeconds(60), now.plusSeconds(900)));

    assertTrue(inTx(u -> schedules.renewLease(u, id, "instance-a", now.plusSeconds(120))));
    assertFalse(inTx(u -> schedules.renewLease(u, id, "instance-b", now.plusSeconds(120))));
    assertFalse(inTx(u -> schedules.release(u, id, "instance-b", now, RunStatus.SUCCEEDED)));
    assertTrue(inTx(u -> schedules.release(u, id, "instance-a", now, RunStatus.SUCCEEDED)));

    ScheduleRow row = find(id);
    assertNull(row.lockedBy());
    assertNull(row.lockedUntil());
    assertEquals("SUCCEEDED", row.lastStatus());
    assertEquals(now, row.lastRunAt());
  }

  @Test
  void expiredLeasesAreFoundAndTheirRunsAbandoned() {
    long id = insertDue("Crashed");
    Instant now = now();
    uow.run(u -> {
      schedules.lease(u, id, "dead-instance", now.minusSeconds(5), now.plusSeconds(900));
      runs.insert(u, scheduled(id, now.minusSeconds(60), 1, now.minusSeconds(60)));
    });

    uow.run(u -> {
      List<ScheduleRow> expired = schedules.lockExpiredLeases(u, now);
      assertEquals(List.of(id), expired.stream().map(ScheduleRow::id).toList());
      var abandoned = runs.abandonRunning(u, id, now, "lease of dead-instance expired");
      assertEquals(1, abandoned.size());
      schedules.clearLease(u, id, now, RunStatus.ABANDONED);
    });

    var run = uow.executeReadOnly(u -> runs.findBySchedule(u, id, 10)).getFirst();
    assertEquals(RunStatus.ABANDONED, run.status());
    assertEquals("lease of dead-instance expired", run.errorMessage());
    assertNull(find(id).lockedBy());
  }

  // ---- runs ----

  @Test
  void theSameFireCannotBeRecordedTwice() {
    long id = insertDue("Fire once");
    Instant now = now();
    Instant fire = now.minusSeconds(60);
    uow.run(u -> runs.insert(u, scheduled(id, fire, 1, now)));
    assertThrows(DataAccessException.class, () -> uow.run(u -> runs.insert(u, scheduled(id, fire, 1, now))));
    // a retry of that fire is a different attempt, and manual runs never collide
    uow.run(u -> {
      runs.insert(u, scheduled(id, fire, 2, now));
      runs.insert(u, manual(id, 1, now));
      runs.insert(u, manual(id, 1, now));
    });
    assertEquals(4, uow.executeReadOnly(u -> runs.findBySchedule(u, id, 10)).size());
  }

  @Test
  void manualRunsRecordWhoAskedAndWaitForAFreeSchedule() {
    long id = insertDue("Manual");
    Instant now = now();
    long runId = uow.execute(u -> runs.insert(u, manual(id, 2, now)));

    // schedule leased by a running fire: the manual run stays queued
    uow.run(u -> schedules.lease(u, id, "instance-a", now.plusSeconds(60)));
    assertEquals(List.of(), uow.execute(u -> runs.lockQueued(u, now, 10)));

    uow.run(u -> schedules.release(u, id, "instance-a", now, RunStatus.SUCCEEDED));
    var queued = uow.execute(u -> runs.lockQueued(u, now, 10));
    assertEquals(1, queued.size());
    assertEquals(runId, queued.getFirst().runId());
    assertEquals(2L, queued.getFirst().requestedBy());
    assertEquals(id, queued.getFirst().schedule().id());
    assertEquals(now, queued.getFirst().requestedAt());

    uow.run(u -> {
      runs.markStarted(u, runId, "instance-b", now);
      runs.finish(u, runId, RunStatus.SUCCEEDED, now.plusSeconds(2), 2000L, "x".repeat(1500), null, null);
    });
    var run = uow.executeReadOnly(u -> runs.findById(u, runId)).orElseThrow();
    assertEquals(RunStatus.SUCCEEDED, run.status());
    assertEquals(TriggerType.MANUAL, run.trigger());
    assertEquals(2L, run.requestedBy());
    assertEquals("instance-b", run.instanceId());
    assertEquals(1000, run.resultSummary().length());
    assertEquals(2000L, run.durationMs());
  }

  @Test
  void manualRunsRequireARequesterAndOnlyThem() {
    assertThrows(IllegalArgumentException.class, () -> new NewRun(1, "test.job", null, TriggerType.MANUAL, null,
        null, RunStatus.QUEUED, 1, Instant.EPOCH, Instant.EPOCH, null, null));
    assertThrows(IllegalArgumentException.class, () -> new NewRun(1, "test.job", null, TriggerType.SCHEDULE, 1L,
        null, RunStatus.RUNNING, 1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, "a"));
  }

  @Test
  void pruningDeletesOnlyOldFinishedRuns() {
    long id = insertDue("Prune");
    Instant now = now();
    uow.run(u -> {
      long old = runs.insert(u, scheduled(id, now.minus(Duration.ofDays(100)), 1, now.minus(Duration.ofDays(100))));
      runs.finish(u, old, RunStatus.SUCCEEDED, now.minus(Duration.ofDays(100)), 10L, null, null, null);
      long recent = runs.insert(u, scheduled(id, now.minusSeconds(60), 1, now));
      runs.finish(u, recent, RunStatus.FAILED, now, 10L, null, "boom", "stack");
      runs.insert(u, scheduled(id, now.minus(Duration.ofDays(200)), 1, now)); // still RUNNING: kept
    });
    assertEquals(1, (int) uow.execute(u -> runs.deleteFinishedBefore(u, now.minus(Duration.ofDays(90)), 100)));
    assertEquals(2, uow.executeReadOnly(u -> runs.findBySchedule(u, id, 10)).size());
  }

  // ---- SYSTEM user ----

  @Test
  void systemUserIsCreatedOnceDisabledAndSelfCreated() throws SQLException {
    long id = stack.services().get(SystemActor.class).userId();
    assertEquals(3, id); // after the seeded users 1 and 2
    assertEquals(id, stack.services().get(SystemActor.class).userId());

    try (Connection c = stack.dataSource().getConnection(); Statement st = c.createStatement();
        var rs = st.executeQuery("SELECT user_no, is_enabled, created_by, updated_by FROM users WHERE id = " + id)) {
      assertTrue(rs.next());
      assertEquals("SYSTEM", rs.getString("user_no"));
      assertFalse(rs.getBoolean("is_enabled"));
      assertEquals(id, rs.getLong("created_by"));
      assertEquals(id, rs.getLong("updated_by"));
    }
    // another server instance finds the same row instead of inserting a second one
    assertEquals(id, new SystemUserService(uow,
        new EnsureSystemUserOperation(
            new UserAccountRepository())).userId());
    assertEquals(1, uow.executeReadOnly(u -> Jdbc.queryFirst(u, "SELECT COUNT(*) FROM users WHERE user_no = 'SYSTEM'",
        rs -> rs.getInt(1))).orElseThrow());
  }

  @Test
  void systemUserCannotGetAPassword() {
    var e = assertThrows(ValidationFailedException.class,
        () -> stack.users().setPassword("SYSTEM", "a long enough password".toCharArray()));
    assertEquals("RESERVED_USER", e.fieldErrors().getFirst().code());
  }
}

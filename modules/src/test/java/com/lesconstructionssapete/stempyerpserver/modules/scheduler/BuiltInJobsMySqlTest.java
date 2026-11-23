package com.lesconstructionssapete.stempyerpserver.modules.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.SchedulerSettings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository.NewRun;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.RunRepository.RunRow;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal.ScheduleRepository;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * The jobs that ship with the server: their default schedules, and what they
 * do when run (started by hand here, as an administrator would).
 */
class BuiltInJobsMySqlTest {

  private static final SchedulerSettings SETTINGS = new SchedulerSettings(true, Duration.ofMillis(100), 2,
      Duration.ofSeconds(3), Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofDays(90), "instance-a");

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private final ScheduleRepository schedules = new ScheduleRepository();
  private final RunRepository runs = new RunRepository();

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(BuiltInJobsMySqlTest.class, AuthSettings.defaults(), BuiltInJobsMySqlTest::seed);
    uow = new UnitOfWorkFactory(stack.dataSource());
    stack.host().startScheduler(SETTINGS);
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
          (1, 1, 'U1', 'Admin', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1)");
  }

  // ---- helpers ----

  private long scheduleOf(String jobKey) {
    return uow.executeReadOnly(u -> Jdbc.queryFirst(u, "SELECT id FROM scheduled_jobs WHERE job_key = ?",
        rs -> rs.getLong(1), jobKey)).orElseThrow(() -> new AssertionError("no schedule for " + jobKey));
  }

  /** Queues a manual run as U1 and waits for it to end. */
  private RunRow runByHand(String jobKey, String parameters) {
    long scheduleId = scheduleOf(jobKey);
    long runId = uow.execute(u -> {
      Instant now = schedules.now(u);
      return runs.insert(u, new NewRun(scheduleId, jobKey, null, TriggerType.MANUAL, 1L, parameters,
          RunStatus.QUEUED, 1, now, now, null, null));
    });
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      RunRow run = uow.executeReadOnly(u -> runs.findById(u, runId)).orElseThrow();
      if (run.status().isFinished()) {
        return run;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return fail("run " + runId + " of " + jobKey + " did not finish");
  }

  private int count(String sql) {
    return uow.executeReadOnly(u -> Jdbc.queryFirst(u, sql, rs -> rs.getInt(1))).orElseThrow();
  }

  // ---- default schedules ----

  @Test
  void bothJobsComeWithAScheduleOwnedBySystem() {
    long system = stack.services().get(SystemActor.class).userId();
    var rows = uow.executeReadOnly(u -> Jdbc.queryList(u, """
        SELECT job_key, cron_expression, time_zone, company_id, created_by FROM scheduled_jobs ORDER BY job_key""",
        rs -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), String.valueOf(rs.getObject(4)),
            rs.getLong(5))));
    assertEquals(List.of(
        List.of("scheduler.prune-runs", "30 3 * * *", "America/Toronto", "null", system),
        List.of("users.purge-expired-sessions", "15 * * * *", "America/Toronto", "null", system)), rows);
  }

  // ---- scheduler.prune-runs ----

  @Test
  void pruneDeletesOnlyFinishedRunsOlderThanTheRetention() {
    long target = uow.execute(u -> Jdbc.insert(u, """
        INSERT INTO scheduled_jobs (job_key, name, schedule_type, cron_expression, time_zone, is_enabled)
        VALUES ('some.job', 'History', 'CRON', '0 0 * * *', 'UTC', FALSE)"""));
    uow.run(u -> {
      Instant now = schedules.now(u);
      for (int days : new int[] { 200, 100, 31, 5 }) {
        Instant at = now.minus(Duration.ofDays(days));
        long id = runs.insert(u, new NewRun(target, "some.job", null, TriggerType.SCHEDULE, null, null,
            RunStatus.RUNNING, 1, at, at, at, "x"));
        runs.finish(u, id, RunStatus.SUCCEEDED, at, 1L, null, null, null);
      }
      Instant old = now.minus(Duration.ofDays(300)); // still RUNNING: never pruned
      runs.insert(u, new NewRun(target, "some.job", null, TriggerType.SCHEDULE, null, null, RunStatus.RUNNING, 1,
          old, old, old, "x"));
    });
    String remaining = "SELECT COUNT(*) FROM scheduled_job_runs WHERE schedule_id = " + target;

    RunRow defaultRetention = runByHand("scheduler.prune-runs", null); // P90D from the settings
    assertEquals(RunStatus.SUCCEEDED, defaultRetention.status(), defaultRetention.errorMessage());
    assertTrue(defaultRetention.resultSummary().startsWith("2 run(s) finished before"),
        defaultRetention.resultSummary());
    assertEquals(3, count(remaining));

    RunRow override = runByHand("scheduler.prune-runs", "{\"retention\": \"P30D\"}");
    assertTrue(override.resultSummary().startsWith("1 run(s)"), override.resultSummary());
    assertTrue(override.resultSummary().endsWith("(retention PT720H)"), override.resultSummary());
    assertEquals(2, count(remaining));
  }

  @Test
  void pruneRejectsABadRetention() {
    RunRow run = runByHand("scheduler.prune-runs", "{\"retention\": \"PT1H\"}");
    assertEquals(RunStatus.FAILED, run.status());
    assertTrue(run.errorMessage().contains("parameters.retention"), run.errorMessage());
  }

  // ---- users.purge-expired-sessions ----

  @Test
  void sessionPurgeKeepsOnlyLiveSessions() {
    uow.run(u -> Jdbc.update(u, """
        INSERT INTO user_sessions (user_id, token_hash, created_at, last_seen_at, expires_at, revoked_at) VALUES
          (1, UNHEX(SHA2('live', 256)),    UTC_TIMESTAMP() - INTERVAL 1 HOUR, UTC_TIMESTAMP(),
              UTC_TIMESTAMP() + INTERVAL 1 DAY, NULL),
          (1, UNHEX(SHA2('revoked', 256)), UTC_TIMESTAMP() - INTERVAL 1 HOUR, UTC_TIMESTAMP(),
              UTC_TIMESTAMP() + INTERVAL 1 DAY, UTC_TIMESTAMP()),
          (1, UNHEX(SHA2('expired', 256)), UTC_TIMESTAMP() - INTERVAL 8 DAY, UTC_TIMESTAMP() - INTERVAL 1 HOUR,
              UTC_TIMESTAMP() - INTERVAL 1 MINUTE, NULL),
          (1, UNHEX(SHA2('idle', 256)),    UTC_TIMESTAMP() - INTERVAL 1 DAY, UTC_TIMESTAMP() - INTERVAL 9 HOUR,
              UTC_TIMESTAMP() + INTERVAL 5 DAY, NULL)"""));

    RunRow run = runByHand("users.purge-expired-sessions", null);
    assertEquals(RunStatus.SUCCEEDED, run.status(), run.errorMessage());
    assertEquals("3 session(s) deleted", run.resultSummary());
    assertEquals(1, count("SELECT COUNT(*) FROM user_sessions WHERE token_hash = UNHEX(SHA2('live', 256))"));
    assertEquals(1, count("SELECT COUNT(*) FROM user_sessions"));
  }
}

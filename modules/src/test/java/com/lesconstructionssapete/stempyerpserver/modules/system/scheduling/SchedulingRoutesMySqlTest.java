package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;

/**
 * The scheduler admin API over HTTP: permissions and scopes, validation,
 * versions, previews, manual runs and history. The scheduler is not started:
 * these tests are about what the API stores (the engine has its own tests).
 *
 * Users: U1 administrator of ACME (every permission), U2 scheduling manager
 * of ACME (scheduler.schedule.read/manage/run only), U3 scheduling reader of ACME, U4
 * scheduling manager of OTHER.
 */
class SchedulingRoutesMySqlTest {

  private static final String PASSWORD = "correct horse battery";
  private static final long ACME = 1;
  private static final long OTHER = 2;

  /** Test job types: one company-only and protected by a permission, one for any scope with parameter rules. */
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
      scheduler.register(JobType.of("test.report", "Company report", ctx -> {
      }).withScope(JobType.Scope.COMPANY).withPermission("masterdata.product.update"));
      scheduler.register(JobType.of("test.any", "Anything", ctx -> {
      }).withParameters(p -> {
        if (p.has("days") && !p.get("days").isInt()) {
          throw ValidationFailedException.field("parameters.days", "INVALID_TYPE", "must be a whole number");
        }
      }));
    }
  }

  private static MySqlTestStack stack;
  private static String admin;
  private static String manager;
  private static String reader;
  private static String outsider;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(SchedulingRoutesMySqlTest.class, AuthSettings.defaults(),
        SchedulingRoutesMySqlTest::seed, List.of(new TestJobsModule()));
    for (String userNo : new String[] { "U1", "U2", "U3", "U4" }) {
      stack.users().setPassword(userNo, PASSWORD.toCharArray());
    }
    admin = stack.login("U1", PASSWORD);
    manager = stack.login("U2", PASSWORD);
    reader = stack.login("U3", PASSWORD);
    outsider = stack.login("U4", PASSWORD);
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, description, is_enabled) VALUES"
        + " (1, 'ACME', 'Acme Inc.', TRUE), (2, 'OTHER', 'Other Ltd.', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1),
          (2, 1, 'U2', 'Scheduling manager', 1, 1),
          (3, 1, 'U3', 'Scheduling reader', 1, 1),
          (4, 2, 'U4', 'Other manager', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1), (2, 1), (3, 1), (4, 2)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'SCHED_MANAGER', 'Manager'), (11, 'SCHED_READER', 'Reader')");
    st.execute("""
        INSERT INTO role_permissions (role_id, permission) VALUES
          (10, 'scheduler.schedule.read'), (10, 'scheduler.schedule.manage'), (10, 'scheduler.schedule.run'), (11, 'scheduler.schedule.read')""");
    st.execute("""
        INSERT INTO user_company_roles (user_id, company_id, role_id)
        SELECT 1, 1, id FROM roles WHERE code = 'ADMINISTRATOR'""");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (2, 1, 10), (3, 1, 11), (4, 2, 10)");
  }

  // ---- helpers ----

  private static HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
    return stack.send(method, path, body, token, token.equals(outsider) ? OTHER : ACME);
  }

  private static JsonNode json(HttpResponse<String> r) throws Exception {
    return MySqlTestStack.json(r);
  }

  private static JsonNode ok(int status, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    return r.body().isEmpty() ? null : json(r);
  }

  private static void rejected(int status, String code, HttpResponse<String> r) throws Exception {
    assertEquals(status, r.statusCode(), r.body());
    assertEquals(code, json(r).get("code").asText(), r.body());
  }

  /** field:code of every error of a 422. */
  private static List<String> errors(HttpResponse<String> r) throws Exception {
    assertEquals(422, r.statusCode(), r.body());
    List<String> out = new ArrayList<>();
    json(r).get("errors").forEach(e -> out.add(e.get("field").asText() + ":" + e.get("code").asText()));
    return out;
  }

  private static int counter;

  /** Creates a company CRON schedule of test.any as the manager; returns it. */
  private static JsonNode createCompanySchedule(String extraJson) throws Exception {
    String body = "{\"name\": \"Schedule " + (++counter) + "\", \"jobKey\": \"test.any\", \"scheduleType\": \"CRON\","
        + " \"cronExpression\": \"0 7 * * *\"" + (extraJson == null ? "" : ", " + extraJson) + "}";
    return ok(201, call("POST", "/scheduler/schedules", body, manager));
  }

  private static long systemScheduleId(String jobKey) throws Exception {
    for (JsonNode s : ok(200, call("GET", "/scheduler/schedules?scope=SYSTEM", null, admin)).get("data")) {
      if (s.get("jobKey").asText().equals(jobKey)) {
        return s.get("id").asLong();
      }
    }
    // the scheduler is not started, so default schedules are not seeded: make one
    return ok(201, call("POST", "/scheduler/schedules", "{\"name\": \"Purge " + jobKey + "\", \"jobKey\": \""
        + jobKey + "\", \"scope\": \"SYSTEM\", \"scheduleType\": \"INTERVAL\", \"interval\": \"PT1H\"}", admin))
        .get("id").asLong();
  }

  private static void sql(String sql) throws SQLException {
    try (Connection c = stack.dataSource().getConnection(); Statement st = c.createStatement()) {
      st.execute(sql);
      c.commit();
    }
  }

  // ---- job types ----

  @Test
  void jobTypesShowScopeAndPermission() throws Exception {
    JsonNode types = ok(200, call("GET", "/scheduler/job-types", null, reader));
    List<String> keys = new ArrayList<>();
    types.forEach(t -> keys.add(t.get("key").asText()));
    assertTrue(keys.containsAll(List.of("scheduler.prune-runs", "users.purge-expired-sessions", "test.any",
        "test.report")), keys.toString());
    for (JsonNode t : types) {
      if (t.get("key").asText().equals("test.report")) {
        assertEquals("COMPANY", t.get("scope").asText());
        assertEquals("masterdata.product.update", t.get("permission").asText());
      }
      if (t.get("key").asText().equals("scheduler.prune-runs")) {
        assertEquals("SYSTEM", t.get("scope").asText());
        assertEquals("30 3 * * *", t.get("defaultSchedule").get("expression").asText());
      }
    }
  }

  // ---- create ----

  @Test
  void createFillsDefaultsAndReturnsTheSchedule() throws Exception {
    var r = call("POST", "/scheduler/schedules", """
        {"name": "Nightly report", "jobKey": "test.any", "scheduleType": "CRON", "cronExpression": "0 7 * * MON-FRI",
         "parameters": {"days": 7}, "maxRetries": 2}""", manager);
    JsonNode s = ok(201, r);
    assertEquals("/scheduler/schedules/" + s.get("id").asLong(), r.headers().firstValue("Location").orElseThrow());
    assertEquals("COMPANY", s.get("scope").asText());
    assertEquals(0, s.get("version").asInt());
    assertTrue(s.get("jobRegistered").asBoolean());
    assertEquals("America/Toronto", s.get("timeZone").asText());
    assertTrue(s.get("isEnabled").asBoolean());
    assertEquals("FIRE_ONCE", s.get("misfirePolicy").asText());
    assertEquals(300, s.get("misfireThresholdSeconds").asInt());
    assertEquals(2, s.get("maxRetries").asInt());
    assertEquals(7, s.get("parameters").get("days").asInt());
    assertEquals(2, s.get("createdBy").asLong());
    assertTrue(s.get("runningOn").isNull());
  }

  @Test
  void createReportsEveryProblemAtOnce() throws Exception {
    var r = call("POST", "/scheduler/schedules", """
        {"jobKey": "test.any", "scheduleType": "CRON", "cronExpression": "0 25 * * *", "interval": "PT1H",
         "timeZone": "Mars/Olympus", "maxRetries": 99, "startAt": "tomorrow", "id": 5, "colour": "red"}""", manager);
    assertEquals(List.of("maxRetries:OUT_OF_RANGE", "startAt:INVALID_FORMAT", "id:READ_ONLY", "colour:UNKNOWN_FIELD",
        "name:REQUIRED", "interval:NOT_ALLOWED", "cronExpression:INVALID_CRON", "timeZone:INVALID_TIME_ZONE"),
        errors(r));
  }

  @Test
  void createChecksTheJobType() throws Exception {
    assertEquals(List.of("jobKey:UNKNOWN_JOB"), errors(call("POST", "/scheduler/schedules",
        "{\"name\": \"x\", \"jobKey\": \"no.such-job\", \"scheduleType\": \"CRON\", \"cronExpression\": \"@daily\"}",
        manager)));
    // a SYSTEM-only job cannot be scheduled for a company
    assertEquals(List.of("scope:NOT_ALLOWED_FOR_JOB"), errors(call("POST", "/scheduler/schedules",
        "{\"name\": \"x\", \"jobKey\": \"users.purge-expired-sessions\", \"scheduleType\": \"CRON\","
            + " \"cronExpression\": \"@daily\"}", manager)));
    // the job type's own parameter rules
    assertEquals(List.of("parameters.days:INVALID_TYPE"), errors(call("POST", "/scheduler/schedules",
        "{\"name\": \"x\", \"jobKey\": \"test.any\", \"scheduleType\": \"CRON\", \"cronExpression\": \"@daily\","
            + " \"parameters\": {\"days\": \"seven\"}}", manager)));
  }

  @Test
  void aJobsOwnPermissionIsRequiredToScheduleIt() throws Exception {
    String body = "{\"name\": \"Report " + (++counter) + "\", \"jobKey\": \"test.report\", \"scheduleType\": \"CRON\","
        + " \"cronExpression\": \"@daily\"}";
    rejected(403, "PERMISSION_DENIED", call("POST", "/scheduler/schedules", body, manager)); // lacks product.update
    ok(201, call("POST", "/scheduler/schedules", body, admin));
  }

  @Test
  void namesAreUniquePerCompany() throws Exception {
    JsonNode s = createCompanySchedule(null);
    String body = "{\"name\": \"" + s.get("name").asText() + "\", \"jobKey\": \"test.any\", \"scheduleType\": \"CRON\","
        + " \"cronExpression\": \"@daily\"}";
    assertEquals(List.of("name:ALREADY_EXISTS"), errors(call("POST", "/scheduler/schedules", body, manager)));
    ok(201, call("POST", "/scheduler/schedules", body, outsider)); // another company may use it
  }

  // ---- who sees what ----

  @Test
  void systemSchedulesNeedSchedulerSystem() throws Exception {
    long system = systemScheduleId("users.purge-expired-sessions");
    rejected(403, "PERMISSION_DENIED", call("POST", "/scheduler/schedules",
        "{\"name\": \"Mine\", \"jobKey\": \"test.any\", \"scope\": \"SYSTEM\", \"scheduleType\": \"INTERVAL\","
            + " \"interval\": \"PT1H\"}", manager));
    rejected(404, "NOT_FOUND", call("GET", "/scheduler/schedules/" + system, null, manager));
    rejected(403, "PERMISSION_DENIED", call("GET", "/scheduler/schedules?scope=SYSTEM", null, manager));

    List<String> managerSees = new ArrayList<>();
    ok(200, call("GET", "/scheduler/schedules", null, manager)).get("data")
        .forEach(s -> managerSees.add(s.get("scope").asText()));
    assertFalse(managerSees.contains("SYSTEM"));

    List<String> adminSees = new ArrayList<>();
    ok(200, call("GET", "/scheduler/schedules?limit=500", null, admin)).get("data")
        .forEach(s -> adminSees.add(s.get("scope").asText()));
    assertTrue(adminSees.contains("SYSTEM") && adminSees.contains("COMPANY"), adminSees.toString());
  }

  @Test
  void otherCompaniesSchedulesDoNotExist() throws Exception {
    long id = createCompanySchedule(null).get("id").asLong();
    rejected(404, "NOT_FOUND", call("GET", "/scheduler/schedules/" + id, null, outsider));
    rejected(404, "NOT_FOUND", call("POST", "/scheduler/schedules/" + id + "/runs", null, outsider));
    ok(200, call("GET", "/scheduler/schedules", null, outsider)).get("data")
        .forEach(s -> assertFalse(s.get("id").asLong() == id));
  }

  @Test
  void readersCanReadButNotChangeOrRun() throws Exception {
    JsonNode s = createCompanySchedule(null);
    long id = s.get("id").asLong();
    ok(200, call("GET", "/scheduler/schedules/" + id, null, reader));
    rejected(403, "PERMISSION_DENIED", call("PATCH", "/scheduler/schedules/" + id,
        "{\"version\": 0, \"isEnabled\": false}", reader));
    rejected(403, "PERMISSION_DENIED", call("POST", "/scheduler/schedules/" + id + "/runs", null, reader));
    rejected(403, "PERMISSION_DENIED", call("DELETE", "/scheduler/schedules/" + id + "?version=0", null, reader));
  }

  // ---- change and delete ----

  @Test
  void patchNeedsTheCurrentVersionAndKeepsWhatIsNotSent() throws Exception {
    long id = createCompanySchedule("\"description\": \"keep me\"").get("id").asLong();
    String path = "/scheduler/schedules/" + id;

    assertEquals(List.of("version:REQUIRED"), errors(call("PATCH", path, "{\"isEnabled\": false}", manager)));
    rejected(409, "VERSION_CONFLICT", call("PATCH", path, "{\"version\": 7, \"isEnabled\": false}", manager));

    JsonNode changed = ok(200, call("PATCH", path,
        "{\"version\": 0, \"cronExpression\": \"*/30 * * * *\", \"timeoutSeconds\": 120}", manager));
    assertEquals(1, changed.get("version").asInt());
    assertEquals("*/30 * * * *", changed.get("cronExpression").asText());
    assertEquals(120, changed.get("timeoutSeconds").asInt());
    assertEquals("keep me", changed.get("description").asText());

    // switching kind: the old expression must be cleared explicitly
    assertEquals(List.of("cronExpression:NOT_ALLOWED"), errors(call("PATCH", path,
        "{\"version\": 1, \"scheduleType\": \"INTERVAL\", \"interval\": \"PT15M\"}", manager)));
    JsonNode interval = ok(200, call("PATCH", path,
        "{\"version\": 1, \"scheduleType\": \"INTERVAL\", \"interval\": \"PT15M\", \"cronExpression\": null,"
            + " \"description\": null, \"timeoutSeconds\": null}", manager));
    assertEquals("INTERVAL", interval.get("scheduleType").asText());
    assertTrue(interval.get("description").isNull());
    assertTrue(interval.get("timeoutSeconds").isNull());

    assertEquals(List.of("scope:READ_ONLY"), errors(call("PATCH", path, "{\"version\": 2, \"scope\": \"SYSTEM\"}",
        manager)));
    assertEquals(List.of("endAt:BEFORE_START"), errors(call("PATCH", path, "{\"version\": 2,"
        + " \"startAt\": \"2027-01-02T00:00:00Z\", \"endAt\": \"2027-01-01T00:00:00Z\"}", manager)));
  }

  @Test
  void deleteIsVersionedAndEndsQueuedRuns() throws Exception {
    long id = createCompanySchedule(null).get("id").asLong();
    String path = "/scheduler/schedules/" + id;
    long runId = ok(202, call("POST", path + "/runs", null, manager)).get("id").asLong();

    assertEquals(List.of("version:REQUIRED"), errors(call("DELETE", path, null, manager)));
    rejected(409, "VERSION_CONFLICT", call("DELETE", path + "?version=3", null, manager));
    ok(204, call("DELETE", path + "?version=0", null, manager));

    rejected(404, "NOT_FOUND", call("GET", path, null, manager));
    JsonNode run = ok(200, call("GET", "/scheduler/runs/" + runId, null, manager));
    assertEquals("SKIPPED", run.get("status").asText());
    assertEquals("Schedule deleted before the run started", run.get("errorMessage").asText());
  }

  // ---- previews ----

  @Test
  void previewOfASavedSchedule() throws Exception {
    long id = createCompanySchedule(null).get("id").asLong(); // 07:00 America/Toronto
    JsonNode p = ok(200, call("GET", "/scheduler/schedules/" + id + "/preview?count=3", null, reader));
    assertEquals("America/Toronto", p.get("timeZone").asText());
    assertEquals(3, p.get("times").size());
    p.get("times").forEach(t -> assertTrue(t.get("local").asText().matches(".*T07:00-0[45]:00"), t.toString()));
    rejected(400, "INVALID_PARAMETER", call("GET", "/scheduler/schedules/" + id + "/preview?count=500", null, reader));
  }

  @Test
  void previewOfAnUnsavedSchedule() throws Exception {
    JsonNode p = ok(200, call("POST", "/scheduler/preview", """
        {"scheduleType": "INTERVAL", "interval": "PT15M", "startAt": "2030-01-01T10:00:00Z",
         "endAt": "2030-01-01T10:30:00Z", "count": 10}""", manager));
    List<String> at = new ArrayList<>();
    p.get("times").forEach(t -> at.add(t.get("at").asText()));
    assertEquals(List.of("2030-01-01T10:00:00Z", "2030-01-01T10:15:00Z", "2030-01-01T10:30:00Z"), at);

    assertEquals(List.of("cronExpression:INVALID_CRON"), errors(call("POST", "/scheduler/preview",
        "{\"scheduleType\": \"CRON\", \"cronExpression\": \"0 0 30 2 *\"}", manager)));
    rejected(403, "PERMISSION_DENIED", call("POST", "/scheduler/preview",
        "{\"scheduleType\": \"CRON\", \"cronExpression\": \"@daily\"}", reader));
  }

  // ---- manual runs and history ----

  @Test
  void runNowQueuesARunActingAsTheCaller() throws Exception {
    long id = createCompanySchedule("\"isEnabled\": false").get("id").asLong(); // disabled: still runnable by hand
    var r = call("POST", "/scheduler/schedules/" + id + "/runs", "{\"parameters\": {\"days\": 3}}", manager);
    JsonNode run = ok(202, r);
    assertEquals("/scheduler/runs/" + run.get("id").asLong(), r.headers().firstValue("Location").orElseThrow());
    assertEquals("QUEUED", run.get("status").asText());
    assertEquals("MANUAL", run.get("trigger").asText());
    assertEquals(2, run.get("requestedBy").asLong());
    assertEquals(3, run.get("parametersOverride").get("days").asInt());

    rejected(409, "RUN_ALREADY_QUEUED", call("POST", "/scheduler/schedules/" + id + "/runs", null, manager));

    JsonNode history = ok(200, call("GET", "/scheduler/runs?scheduleId=" + id + "&status=queued", null, reader));
    assertEquals(1, history.get("data").size());
    assertEquals(50, history.get("page").get("limit").asInt());
  }

  @Test
  void runNowChecksTheBodyAndTheJob() throws Exception {
    long id = createCompanySchedule(null).get("id").asLong();
    String path = "/scheduler/schedules/" + id + "/runs";
    assertEquals(List.of("parameters.days:INVALID_TYPE"), errors(call("POST", path,
        "{\"parameters\": {\"days\": \"x\"}}", manager)));
    assertEquals(List.of("when:UNKNOWN_FIELD"), errors(call("POST", path, "{\"when\": \"now\"}", manager)));

    sql("UPDATE scheduled_jobs SET job_key = 'gone.job' WHERE id = " + id); // e.g. an older server's job
    assertEquals(List.of("scheduleId:UNKNOWN_JOB"), errors(call("POST", path, null, manager)));
    assertFalse(ok(200, call("GET", "/scheduler/schedules/" + id, null, manager)).get("jobRegistered").asBoolean());
  }

  @Test
  void systemRunsAndStackTracesAreForAdministrators() throws Exception {
    long system = systemScheduleId("users.purge-expired-sessions");
    long runId = ok(202, call("POST", "/scheduler/schedules/" + system + "/runs", null, admin)).get("id").asLong();
    // without scheduler.system.manage, system schedules and their runs are invisible
    rejected(404, "NOT_FOUND", call("POST", "/scheduler/schedules/" + system + "/runs", null, manager));
    rejected(404, "NOT_FOUND", call("GET", "/scheduler/runs/" + runId, null, manager));

    long companyId = createCompanySchedule(null).get("id").asLong();
    long failed = ok(202, call("POST", "/scheduler/schedules/" + companyId + "/runs", null, manager)).get("id").asLong();
    sql("UPDATE scheduled_job_runs SET status = 'FAILED', error_message = 'boom', error_detail = 'at Foo.bar()'"
        + " WHERE id = " + failed);
    assertTrue(ok(200, call("GET", "/scheduler/runs/" + failed, null, manager)).get("errorDetail").isNull());
    assertEquals("at Foo.bar()", ok(200, call("GET", "/scheduler/runs/" + failed, null, admin)).get("errorDetail")
        .asText());
  }

  @Test
  void badQueryParametersAre400() throws Exception {
    rejected(400, "INVALID_PARAMETER", call("GET", "/scheduler/runs?limit=0", null, reader));
    rejected(400, "INVALID_PARAMETER", call("GET", "/scheduler/runs?status=LOST", null, reader));
    rejected(400, "INVALID_PARAMETER", call("GET", "/scheduler/schedules?scope=GLOBAL", null, reader));
    rejected(404, "NOT_FOUND", call("GET", "/scheduler/schedules/abc", null, reader));
  }
}

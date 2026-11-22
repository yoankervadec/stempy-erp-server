package com.lesconstructionssapete.stempyerpserver.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.FakeDataSource;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;

/**
 * Drives a real Jetty on a random port with plain HTTP, so filters, the
 * exception mapper and JSON settings are tested exactly as wired in
 * production.
 */
class WebServerTest {

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private WebServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private void start(Consumer<Routes> routes) {
    server = new WebServer(new WebServerConfig("127.0.0.1", 0),
        new ExceptionMapper(new ErrorTranslator()), new DevAuthFilter()::handle);
    server.start(routes);
  }

  private HttpResponse<String> send(String method, String path, String body, String... headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (headers.length > 0) {
      b.headers(headers);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** May query widgets, but not see their cost. */
  private static final String[] DEV_USER = {
      DevAuthFilter.USER_HEADER, "7", DevAuthFilter.COMPANY_HEADER, "42",
      DevAuthFilter.PERMISSIONS_HEADER, "widget.widget.read" };

  private static JsonNode json(HttpResponse<String> r) throws IOException {
    return JSON.readTree(r.body());
  }

  // ---- cross-cutting behaviour ----

  @Test
  void healthIsPublicAndEchoesTheCallersRequestId() throws Exception {
    start(routes -> HealthRoutes.register(routes, new HealthChecks()));

    HttpResponse<String> r = send("GET", "/health/live", null, RequestIdFilter.HEADER, "abc-123");

    assertEquals(200, r.statusCode());
    assertEquals("abc-123", r.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
    assertEquals("UP", json(r).get("status").asText());
  }

  @Test
  void protectedRouteWithoutCredentialsIs401ProblemJson() throws Exception {
    start(routes -> routes.get("/me", ctx -> ctx.json(RequestContexts.require(ctx))));

    HttpResponse<String> r = send("GET", "/me", null);

    assertEquals(401, r.statusCode());
    assertTrue(r.headers().firstValue("Content-Type").orElseThrow().startsWith(ApiError.CONTENT_TYPE));
    JsonNode body = json(r);
    assertEquals("UNAUTHENTICATED", body.get("code").asText());
    assertEquals(r.headers().firstValue(RequestIdFilter.HEADER).orElseThrow(), body.get("requestId").asText());
  }

  @Test
  void devAuthHeadersBuildTheRequestContext() throws Exception {
    start(routes -> routes.get("/me", ctx -> ctx.json(RequestContexts.require(ctx))));

    HttpResponse<String> r = send("GET", "/me", null, DevAuthFilter.USER_HEADER, "7",
        DevAuthFilter.COMPANY_HEADER, "42", DevAuthFilter.PERMISSIONS_HEADER, "a.read, b.write");

    assertEquals(200, r.statusCode());
    JsonNode body = json(r);
    assertEquals(7, body.get("userId").asLong());
    assertEquals(42, body.get("companyId").asLong());
    assertEquals(2, body.get("permissions").size());
  }

  @Test
  void malformedDevAuthHeaderIs401() throws Exception {
    start(routes -> routes.get("/me", ctx -> ctx.json(RequestContexts.require(ctx))));

    HttpResponse<String> r = send("GET", "/me", null, DevAuthFilter.USER_HEADER, "7");

    assertEquals(401, r.statusCode());
    assertEquals("DEV_AUTH_INVALID", json(r).get("code").asText());
  }

  @Test
  void validationFailureIs422WithFieldErrors() throws Exception {
    start(routes -> routes.post("/fail", ctx -> {
      throw ValidationFailedException.field("name", "REQUIRED", "is required");
    }));

    HttpResponse<String> r = send("POST", "/fail", "{}");

    assertEquals(422, r.statusCode());
    assertEquals("name", json(r).get("errors").get(0).get("field").asText());
  }

  @Test
  void unexpectedExceptionIsGeneric500WithoutDetails() throws Exception {
    start(routes -> routes.get("/boom", ctx -> {
      throw new IllegalStateException("secret internal detail");
    }));

    HttpResponse<String> r = send("GET", "/boom", null);

    assertEquals(500, r.statusCode());
    assertEquals("INTERNAL_ERROR", json(r).get("code").asText());
    assertFalse(r.body().contains("secret"));
  }

  // ---- EntityEndpoints (QUERY; writes are covered against MySQL) ----

  private FakeDataSource startWidgetQueries(List<Map<String, Object>> rows) {
    FakeDataSource db = new FakeDataSource(rows);
    var permissions = new PermissionRegistry()
        .declare("widget.widget.read", "View widgets")
        .declare("widget.cost.read", "See widget costs");
    var endpoint = endpoints(new UnitOfWorkFactory(db), permissions);
    start(routes -> routes.query("/widgets", endpoint.query(TestWidget.DEF, "widget.widget.read")));
    return db;
  }

  @Test
  void queryReturnsCompanyScopedPageOfReadableFields() throws Exception {
    FakeDataSource db = startWidgetQueries(List.of(
        Map.of("id", 1L, "name", "bolt", "unit_cost", new BigDecimal("0.10")),
        Map.of("id", 2L, "name", "boltx", "unit_cost", new BigDecimal("0.25"))));

    HttpResponse<String> r = send("QUERY", "/widgets",
        "{\"where\":{\"field\":\"name\",\"op\":\"startsWith\",\"value\":\"bo\"},\"page\":{\"limit\":10}}",
        DEV_USER);

    assertEquals(200, r.statusCode(), r.body());
    JsonNode body = json(r);
    assertEquals(2, body.get("data").size());
    assertEquals("bolt", body.get("data").get(0).get("name").asText());
    // cost needs widget.cost.read, which the caller lacks: not selected, not shown
    assertFalse(body.get("data").get(0).has("cost"));
    assertFalse(db.lastSql().contains("unit_cost"));
    assertEquals(10, body.get("page").get("limit").asInt());
    // company from the RequestContext, never from the client
    assertEquals(List.of(42L, "bo%", 10L, 0L), db.lastParams());
  }

  @Test
  void decimalsAreSerializedAsStrings() throws Exception {
    startWidgetQueries(List.of(Map.of("id", 1L, "name", "bolt", "unit_cost", new BigDecimal("1000.50"))));

    HttpResponse<String> r = send("QUERY", "/widgets", "",
        DevAuthFilter.USER_HEADER, "7", DevAuthFilter.COMPANY_HEADER, "42",
        DevAuthFilter.PERMISSIONS_HEADER, "widget.widget.read, widget.cost.read");

    assertEquals(200, r.statusCode(), r.body());
    JsonNode cost = json(r).get("data").get(0).get("cost");
    assertTrue(cost.isTextual());
    assertEquals("1000.50", cost.asText());
  }

  @Test
  void invalidQueryIs422AndNeverTakesAConnection() throws Exception {
    FakeDataSource db = startWidgetQueries(List.of());

    HttpResponse<String> r = send("QUERY", "/widgets",
        "{\"where\":{\"field\":\"password\",\"op\":\"equals\",\"value\":\"x\"}}", DEV_USER);

    assertEquals(422, r.statusCode());
    assertEquals("UNKNOWN_FIELD", json(r).get("errors").get(0).get("code").asText());
    assertEquals(0, db.connectionsOpened());
  }

  @Test
  void malformedJsonIs400() throws Exception {
    FakeDataSource db = startWidgetQueries(List.of());

    HttpResponse<String> r = send("QUERY", "/widgets", "{not json", DEV_USER);

    assertEquals(400, r.statusCode());
    assertEquals(0, db.connectionsOpened());
  }

  @Test
  void queryWithoutCredentialsIs401() throws Exception {
    FakeDataSource db = startWidgetQueries(List.of());

    HttpResponse<String> r = send("QUERY", "/widgets", "");

    assertEquals(401, r.statusCode());
    assertEquals(0, db.connectionsOpened());
  }

  @Test
  void queryWithoutTheRoutePermissionIs403AndNeverTakesAConnection() throws Exception {
    FakeDataSource db = startWidgetQueries(List.of());

    HttpResponse<String> r = send("QUERY", "/widgets", "",
        DevAuthFilter.USER_HEADER, "7", DevAuthFilter.COMPANY_HEADER, "42");

    assertEquals(403, r.statusCode());
    assertEquals("PERMISSION_DENIED", json(r).get("code").asText());
    assertEquals(0, db.connectionsOpened());
  }

  @Test
  void routeWithAnUndeclaredPermissionFailsAtRegistration() {
    var endpoint = endpoints(new UnitOfWorkFactory(new FakeDataSource(List.of())), new PermissionRegistry());
    assertThrows(IllegalStateException.class, () -> endpoint.query(TestWidget.DEF, "widget.widget.reed"));
  }

  private static EntityEndpoints endpoints(UnitOfWorkFactory uowFactory, PermissionRegistry permissions) {
    var eventBus = new EventBus();
    eventBus.freeze();
    return new EntityEndpoints(uowFactory, QueryLimits.defaults(), new PermissionChecker(permissions), eventBus,
        Clock.systemUTC());
  }
}

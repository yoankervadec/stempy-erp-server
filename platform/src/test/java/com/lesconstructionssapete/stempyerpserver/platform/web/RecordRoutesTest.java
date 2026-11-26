package com.lesconstructionssapete.stempyerpserver.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.FakeDataSource;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestPart;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;

/**
 * One record's "_meta", GET {path}/new and the actions in GET /meta
 * (docs/ARCHITECTURE.md §23.9), over real HTTP with a fake database that
 * returns one part.
 */
class RecordRoutesTest {

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private static final Messages MESSAGES = Messages.load(RecordRoutesTest.class.getClassLoader(),
      List.of("metatest"));
  private final HttpClient http = HttpClient.newHttpClient();
  private WebServer server;
  private ResourceCatalog catalog;

  /** A part whose code starts with "L" has a locked name; "Pump" can't be deleted; new ones get a note. */
  private static final RecordRules PART_RULES = new RecordRules() {
    @Override
    public Set<String> locked(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
      return String.valueOf(row.get("code")).startsWith("L") ? Set.of("name") : Set.of();
    }

    @Override
    public boolean canDelete(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
      return !"Pump".equals(row.get("name"));
    }

    @Override
    public Map<String, Object> defaults(UnitOfWork uow, RequestContext rc) {
      return Map.of("note", "new", "notAField", "ignored");
    }
  };

  private static final ResourceAction ARCHIVE = ResourceAction.onRecord("archive").post("/archive")
      .permission("inv.part.archive")
      .param(ActionParam.date("until").required())
      .param(ActionParam.string("reason").maxLength(50))
      .confirm()
      .when(row -> !"Archived".equals(row.get("name")));

  @AfterEach
  @SuppressWarnings("unused")
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private void start(Map<String, Object> part) {
    PermissionRegistry registry = new PermissionRegistry()
        .declare(AuditLog.READ_PERMISSION, "See histories")
        .declare("widget.widget.read", "View widgets")
        .declareEntity("inv.part", "parts")
        .declare("inv.part.archive", "Archive parts")
        .declare("part.note.write", "Write part notes");
    var checker = new PermissionChecker(registry);
    catalog = new ResourceCatalog();
    var eventBus = new EventBus();
    eventBus.freeze();
    var endpoints = new EntityEndpoints(new UnitOfWorkFactory(new FakeDataSource(List.of(part))),
        QueryLimits.defaults(), checker, eventBus, Clock.systemUTC(), new AuditLog(JSON), Optional::empty, catalog,
        MESSAGES);
    server = new WebServer(new WebServerConfig("127.0.0.1", 0), new ExceptionMapper(new ErrorTranslator()),
        new DevAuthFilter()::handle);
    server.start(routes -> {
      catalog.enterModule("inventory");
      endpoints.register(routes, "/widgets", TestWidget.DEF, "widget.widget", Action.READ);
      endpoints.register(routes, "/parts", TestPart.DEF, "inv.part");
      endpoints.rules(TestPart.DEF, PART_RULES);
      endpoints.action(TestPart.DEF, ARCHIVE);
      endpoints.action(TestPart.DEF, ResourceAction.onResource("import").post("/import").permission("inv.part.create"));
      catalog.freeze();
      new MetaRoutes(catalog, checker, MESSAGES).register(routes);
    });
  }

  private static Map<String, Object> part(String code, String name) {
    Map<String, Object> row = new HashMap<>();
    row.put("id", 1L);
    row.put("company_id", 42L);
    row.put("version", 3L);
    row.put("code", code);
    row.put("name", name);
    row.put("note", "a note");
    return row;
  }

  private HttpResponse<String> get(String path, String permissions) throws IOException, InterruptedException {
    List<String> headers = new ArrayList<>(List.of(DevAuthFilter.USER_HEADER, "7", DevAuthFilter.COMPANY_HEADER,
        "42", DevAuthFilter.PERMISSIONS_HEADER, permissions));
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .headers(headers.toArray(String[]::new)).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(String path, String permissions) throws Exception {
    HttpResponse<String> r = get(path, permissions);
    assertEquals(200, r.statusCode(), r.body());
    return JSON.readTree(r.body());
  }

  // ---- _meta ----

  @Test
  void aRecordSaysWhatTheCallerMayDoWithItNow() throws Exception {
    start(part("L-1", "Pump"));

    JsonNode meta = ok("/parts/1", "inv.part.read, inv.part.update, inv.part.delete, inv.part.archive")
        .get("_meta");

    assertTrue(meta.get("can").get("update").asBoolean());
    // the permission is there, the rules say no
    assertFalse(meta.get("can").get("delete").asBoolean());
    assertEquals("[\"name\"]", meta.get("locked").toString());
    // the confirmation filled from the record, in the caller's language (French by default)
    assertEquals("[{\"name\":\"archive\",\"confirm\":\"Archiver la pièce L-1 ?\"}]", meta.get("actions").toString());
  }

  @Test
  void actionsNeedTheirPermissionAndTheirCondition() throws Exception {
    start(part("P-1", "Archived"));

    JsonNode withPermission = ok("/parts/1", "inv.part.read, inv.part.archive").get("_meta");
    JsonNode without = ok("/parts/1", "inv.part.read").get("_meta");

    assertEquals("[]", withPermission.get("actions").toString());
    assertEquals("[]", without.get("actions").toString());
    assertFalse(without.get("can").get("update").asBoolean());
    assertEquals("[]", without.get("locked").toString());
  }

  // ---- GET {path}/new ----

  @Test
  void aNewRecordStartsWithItsDefaults() throws Exception {
    start(part("P-1", "Pump"));

    JsonNode blank = ok("/parts/new", "inv.part.read, inv.part.create");

    // every readable field, at its default or null; the rules' unknown keys are ignored
    assertTrue(blank.has("code"));
    assertTrue(blank.get("code").isNull());
    assertEquals("new", blank.get("note").asText());
    assertFalse(blank.has("notAField"));
    assertTrue(blank.get("_meta").get("new").asBoolean());
  }

  @Test
  void aNewRecordNeedsTheCreatePermissionAndAResourceThatCreates() throws Exception {
    start(part("P-1", "Pump"));

    assertEquals(403, get("/parts/new", "inv.part.read").statusCode());
    // widgets are read-only: no create form
    assertEquals(404, get("/widgets/new", "widget.widget.read").statusCode());
  }

  // ---- actions in GET /meta ----

  @Test
  void metaDescribesTheActionsTheCallerMayRun() throws Exception {
    start(part("P-1", "Pump"));

    JsonNode actions = resource(ok(MetaRoutes.PATH, "inv.part.read, inv.part.create, inv.part.archive"), "part")
        .get("actions");
    JsonNode readerActions = resource(ok(MetaRoutes.PATH, "inv.part.read"), "part").get("actions");

    assertEquals(2, actions.size());
    JsonNode archive = actions.get(0);
    assertEquals("Archiver", archive.get("label").asText());
    assertEquals("record", archive.get("scope").asText());
    assertEquals("POST", archive.get("method").asText());
    assertEquals("/parts/{id}/archive", archive.get("path").asText());
    assertTrue(archive.get("confirm").asBoolean());
    JsonNode until = archive.get("params").get(0);
    assertEquals("until", until.get("name").asText());
    assertEquals("Archivée jusqu'au", until.get("label").asText());
    assertEquals("date", until.get("type").asText());
    assertTrue(until.get("required").asBoolean());
    assertEquals(50, archive.get("params").get(1).get("maxLength").asInt());
    assertEquals("/parts/import", actions.get(1).get("path").asText());
    assertEquals("resource", actions.get(1).get("scope").asText());
    assertEquals("[]", readerActions.toString());
  }

  @Test
  void everyActionTextIsCheckedInEveryLanguage() throws Exception {
    start(part("P-1", "Pump"));

    assertEquals(List.of(), catalog.missingLabels(MESSAGES));
    List<String> missing = catalog.missingLabels(Messages.empty());
    assertTrue(missing.contains("fr: part.action.archive.confirm"), missing.toString());
    assertTrue(missing.contains("en: part.action.import"), missing.toString());
  }

  private static JsonNode resource(JsonNode meta, String key) {
    for (JsonNode r : meta.get("resources")) {
      if (r.get("key").asText().equals(key)) {
        return r;
      }
    }
    throw new AssertionError("no resource " + key);
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryLimits;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.FakeDataSource;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestPart;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestStock;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;

/**
 * GET /meta over real HTTP, with resources registered the way ModuleHost
 * does: two modules, a read-only resource, a hand-written write, a reference.
 */
class MetaRoutesTest {

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private WebServer server;

  @AfterEach
  @SuppressWarnings("unused")
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private static PermissionRegistry permissions() {
    return new PermissionRegistry()
        .declare(AuditLog.READ_PERMISSION, "See histories")
        .declare("widget.widget.read", "View widgets")
        .declare("widget.cost.read", "See widget costs")
        .declareEntity("inv.part", "parts")
        .declare("part.note.write", "Write part notes")
        .declare("inv.stock.read", "View stock")
        .declare("inv.stock.manage", "Change stock")
        .declare("stock.value.read", "See stock value");
  }

  /** Test bundles: i18n/metatest.properties and i18n/metatest_fr.properties (test resources). */
  private final Messages messages = Messages.load(MetaRoutesTest.class.getClassLoader(), List.of("metatest"));

  private void start() {
    PermissionRegistry registry = permissions();
    var checker = new PermissionChecker(registry);
    var catalog = new ResourceCatalog();
    var eventBus = new EventBus();
    eventBus.freeze();
    var endpoints = new EntityEndpoints(new UnitOfWorkFactory(new FakeDataSource(List.of())), QueryLimits.defaults(),
        checker, eventBus, Clock.systemUTC(), new AuditLog(JSON), Optional::empty, catalog, messages);
    server = new WebServer(new WebServerConfig("127.0.0.1", 0), new ExceptionMapper(new ErrorTranslator()),
        new DevAuthFilter()::handle);
    server.start(routes -> {
      catalog.enterModule("widgets");
      endpoints.register(routes, "/widgets", TestWidget.DEF, "widget.widget", Action.READ);
      catalog.enterModule("inventory");
      endpoints.register(routes, "/parts", TestPart.DEF, "inv.part");
      endpoints.register(routes, "/stock", TestStock.DEF, "inv.stock", Action.READ);
      endpoints.declareWrite(TestStock.DEF, Action.UPDATE, "inv.stock.manage");
      catalog.freeze();
      new MetaRoutes(catalog, checker, messages).register(routes);
    });
  }

  private HttpResponse<String> get(String permissions, String... headers) throws IOException, InterruptedException {
    List<String> all = new ArrayList<>(List.of(DevAuthFilter.USER_HEADER, "7", DevAuthFilter.COMPANY_HEADER, "42",
        DevAuthFilter.PERMISSIONS_HEADER, permissions));
    all.addAll(List.of(headers));
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + MetaRoutes.PATH))
        .headers(all.toArray(String[]::new)).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode meta(String permissions, String... headers) throws IOException, InterruptedException {
    HttpResponse<String> r = get(permissions, headers);
    assertEquals(200, r.statusCode(), r.body());
    return JSON.readTree(r.body());
  }

  private static JsonNode resource(JsonNode meta, String key) {
    for (JsonNode r : meta.get("resources")) {
      if (r.get("key").asText().equals(key)) {
        return r;
      }
    }
    return null;
  }

  private static JsonNode field(JsonNode resource, String name) {
    for (JsonNode f : resource.get("fields")) {
      if (f.get("name").asText().equals(name)) {
        return f;
      }
    }
    return null;
  }

  @Test
  void listsOnlyTheResourcesAndFieldsTheCallerCanRead() throws Exception {
    start();

    JsonNode meta = meta("widget.widget.read");

    assertEquals(MetaRoutes.API_VERSION, meta.get("apiVersion").asInt());
    assertEquals(1, meta.get("resources").size());
    JsonNode widget = resource(meta, "widget");
    assertEquals("widgets", widget.get("module").asText());
    assertEquals("/widgets", widget.get("path").asText());
    assertTrue(widget.get("can").get("read").asBoolean());
    assertFalse(widget.get("can").get("create").asBoolean());
    assertFalse(widget.get("can").get("history").asBoolean());
    // cost needs widget.cost.read
    assertTrue(field(widget, "name") != null);
    assertEquals(null, field(widget, "cost"));
    assertEquals("[{\"module\":\"widgets\",\"label\":\"Widgets\",\"resources\":[\"widget\"]}]",
        meta.get("menu").toString());
  }

  @Test
  void describesFieldsWithTheirRulesAndOperators() throws Exception {
    start();

    JsonNode name = field(resource(meta("widget.widget.read"), "widget"), "name");

    assertEquals("string", name.get("type").asText());
    assertTrue(name.get("required").asBoolean());
    assertEquals(5, name.get("maxLength").asInt());
    assertTrue(name.get("sortable").asBoolean());
    assertTrue(name.get("operators").toString().contains("\"startsWith\""));
    assertFalse(name.get("writable").get("create").asBoolean());
  }

  @Test
  void writableFollowsTheActionTheFieldAndItsWritePermission() throws Exception {
    start();

    JsonNode part = resource(meta("inv.part.read, inv.part.create, inv.part.update, inv.part.delete"), "part");

    assertTrue(part.get("can").get("delete").asBoolean());
    assertEquals("id", part.get("idField").asText());
    assertEquals("version", part.get("versionField").asText());
    // immutable: set on create only
    assertEquals("{\"create\":true,\"update\":false}", field(part, "code").get("writable").toString());
    assertEquals("{\"create\":true,\"update\":true}", field(part, "name").get("writable").toString());
    // needs part.note.write
    assertEquals("{\"create\":false,\"update\":false}", field(part, "note").get("writable").toString());
    // server-managed
    assertEquals("{\"create\":false,\"update\":false}", field(part, "createdAt").get("writable").toString());
    assertFalse(field(part, "id").get("writable").get("create").asBoolean());
  }

  @Test
  void aHandWrittenWriteCountsAsTheAction() throws Exception {
    start();

    JsonNode stock = resource(meta("inv.stock.read, inv.stock.manage"), "stock");

    assertFalse(stock.get("can").get("create").asBoolean());
    assertTrue(stock.get("can").get("update").asBoolean());
    assertTrue(field(stock, "quantity").get("writable").get("update").asBoolean());
  }

  @Test
  void aForeignKeyIsAReferenceOnlyWhenTheTargetIsReadable() throws Exception {
    start();

    JsonNode withParts = field(resource(meta("inv.stock.read, inv.part.read"), "stock"), "partId");
    JsonNode withoutParts = field(resource(meta("inv.stock.read"), "stock"), "partId");

    assertEquals("reference", withParts.get("type").asText());
    assertEquals("part", withParts.get("ref").asText());
    assertEquals("integer", withoutParts.get("type").asText());
    assertFalse(withoutParts.has("ref"));
  }

  @Test
  void enumsListTheirValuesAndBoundsAreSent() throws Exception {
    start();

    JsonNode stock = resource(meta("inv.stock.read, stock.value.read, " + AuditLog.READ_PERMISSION), "stock");

    // the default language: French
    assertEquals("[{\"value\":\"AVAILABLE\",\"label\":\"Disponible\"},{\"value\":\"RESERVED\",\"label\":\"Reserved\"}]",
        field(stock, "status").get("values").toString());
    assertEquals("enum", field(stock, "status").get("type").asText());
    assertEquals("0", field(stock, "quantity").get("min").asText());
    assertEquals("100000", field(stock, "quantity").get("max").asText());
    assertTrue(field(stock, "value") != null);
    assertTrue(stock.get("can").get("history").asBoolean());
  }

  @Test
  void theSameAnswerIs304WithItsETag() throws Exception {
    start();
    HttpResponse<String> first = get("widget.widget.read");
    String etag = first.headers().firstValue("ETag").orElseThrow();

    HttpResponse<String> again = get("widget.widget.read", "If-None-Match", etag);
    HttpResponse<String> otherUser = get("widget.widget.read, widget.cost.read", "If-None-Match", etag);

    assertEquals(304, again.statusCode());
    assertEquals("", again.body());
    assertEquals(200, otherUser.statusCode());
    assertFalse(etag.equals(otherUser.headers().firstValue("ETag").orElseThrow()));
  }

  @Test
  void needsAnAuthenticatedCaller() throws Exception {
    start();
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + MetaRoutes.PATH))
        .GET().build();

    assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void catalogRefusesAnEntityTwiceAWriteWithoutReadsAndLateRegistrations() {
    var catalog = new ResourceCatalog();
    assertThrows(IllegalStateException.class,
        () -> catalog.add("/parts", TestPart.DEF, "inv.part", java.util.Set.of(Action.READ)));
    catalog.enterModule("inventory");
    catalog.add("/parts", TestPart.DEF, "inv.part", java.util.Set.of(Action.READ));
    assertThrows(IllegalStateException.class,
        () -> catalog.add("/parts-again", TestPart.DEF, "inv.part", java.util.Set.of(Action.READ)));
    assertThrows(IllegalStateException.class, () -> catalog.declareWrite(TestStock.DEF, Action.UPDATE, "x.y.z"));
    assertThrows(IllegalArgumentException.class, () -> catalog.declareWrite(TestPart.DEF, Action.READ, "x.y.z"));
    catalog.freeze();
    assertThrows(IllegalStateException.class, () -> catalog.enterModule("late"));
  }

  // ---- UI hints (§23.4) ----

  @Test
  void undeclaredHintsGetTheirDefaults() throws Exception {
    start();

    JsonNode widget = resource(meta("widget.widget.read"), "widget");

    // no unique string field: the id names a widget
    assertEquals("id", widget.get("title").asText());
    assertTrue(widget.get("subtitle").isNull());
    // filterable or sortable, readable, not the id
    assertEquals("[\"name\"]", widget.get("listColumns").toString());
    assertEquals("[{\"field\":\"id\",\"dir\":\"desc\"}]", widget.get("defaultSort").toString());
    assertEquals("modal", widget.get("presentation").asText());
  }

  @Test
  void declaredHintsAreSent() throws Exception {
    start();

    JsonNode part = resource(meta("inv.part.read"), "part");

    assertEquals("code", part.get("title").asText());
    assertEquals("name", part.get("subtitle").asText());
    assertEquals("[\"code\",\"name\"]", part.get("listColumns").toString());
    assertEquals("[{\"field\":\"code\",\"dir\":\"asc\"}]", part.get("defaultSort").toString());
    assertEquals("page", part.get("presentation").asText());
    assertEquals("multiline", field(part, "note").get("format").asText());
    assertFalse(field(part, "name").has("format"));
  }

  @Test
  void decimalsCarryTheirScaleAndMoneyItsCurrency() throws Exception {
    start();

    JsonNode stock = resource(meta("inv.stock.read, stock.value.read, inv.part.read"), "stock");

    assertEquals(3, field(stock, "quantity").get("scale").asInt());
    JsonNode value = field(stock, "value");
    assertEquals(MetaRoutes.DEFAULT_SCALE, value.get("scale").asInt());
    assertEquals("money", value.get("format").asText());
    assertEquals("currencyId", value.get("currencyField").asText());
    assertFalse(field(stock, "partId").has("scale"));
    // references() without a relation
    assertEquals("part", field(stock, "replacementId").get("ref").asText());
  }

  @Test
  void hintsOnUnreadableFieldsAreLeftOut() throws Exception {
    start();

    JsonNode stock = resource(meta("inv.stock.read"), "stock");

    // value needs stock.value.read
    assertEquals("[\"partId\",\"status\"]", stock.get("listColumns").toString());
    assertEquals("integer", field(stock, "replacementId").get("type").asText());
  }

  // ---- labels (§23.5) ----

  @Test
  void labelsAreInTheCallersLanguage() throws Exception {
    start();

    JsonNode fr = meta("inv.part.read, inv.stock.read", DevAuthFilter.LANGUAGE_HEADER, "fr");
    JsonNode en = meta("inv.part.read, inv.stock.read", DevAuthFilter.LANGUAGE_HEADER, "en");

    assertEquals("fr", fr.get("language").asText());
    assertEquals("Inventaire", fr.get("menu").get(0).get("label").asText());
    JsonNode part = resource(fr, "part");
    assertEquals("Pièces", part.get("label").asText());
    assertEquals("Pièce", part.get("labelOne").asText());
    assertEquals("Code de pièce", field(part, "code").get("label").asText());
    assertEquals("Parts", resource(en, "part").get("label").asText());
    assertEquals("Part code", field(resource(en, "part"), "code").get("label").asText());
    // a French label missing: the English one
    JsonNode status = field(resource(fr, "stock"), "status").get("values");
    assertEquals("Disponible", status.get(0).get("label").asText());
    // no label anywhere: the humanized name
    assertEquals("Reserved", status.get(1).get("label").asText());
    assertEquals("Part id", field(resource(fr, "stock"), "partId").get("label").asText());
    assertEquals("Stock", resource(fr, "stock").get("labelOne").asText());
  }

  @Test
  void eachLanguageHasItsOwnETag() throws Exception {
    start();
    String fr = get("inv.part.read", DevAuthFilter.LANGUAGE_HEADER, "fr").headers().firstValue("ETag").orElseThrow();
    String en = get("inv.part.read", DevAuthFilter.LANGUAGE_HEADER, "en").headers().firstValue("ETag").orElseThrow();

    assertFalse(fr.equals(en));
  }
}

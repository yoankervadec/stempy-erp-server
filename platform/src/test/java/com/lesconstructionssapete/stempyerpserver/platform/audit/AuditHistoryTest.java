package com.lesconstructionssapete.stempyerpserver.platform.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory.UserSummary;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;

/** What a history shows a given reader: masking by field read permission, and who acted. */
class AuditHistoryTest {

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private static final Instant AT = Instant.parse("2026-11-24T12:00:00Z");

  private static ObjectNode changes() throws Exception {
    // TestWidget.cost needs widget.cost.read; "legacy" is a field the definition no longer has.
    return (ObjectNode) JSON.readTree("""
        {"name": {"old": "Bolt", "new": "Nut"}, "cost": {"old": "1.50", "new": "2.00"},
         "legacy": {"old": 1, "new": 2}}""");
  }

  private static AuditRecord record(Long userId, ObjectNode changes) {
    return new AuditRecord(9, 1L, "widget", 5, "UPDATED", userId, "req-1", AT, changes);
  }

  private static FieldAccess holding(String... permissions) {
    return FieldAccess.forPermissions(Set.of(permissions)::contains);
  }

  @Test
  void hidesTheValuesOfFieldsTheReaderMayNotSee() throws Exception {
    var item = AuditHistory.view(List.of(record(1L, changes())), TestWidget.DEF, holding(), Map.of()).getFirst();
    ObjectNode shown = (ObjectNode) item.get("changes");
    assertEquals("Nut", shown.get("name").get("new").asText());
    assertEquals(JSON.readTree("{\"masked\": true}"), shown.get("cost")); // it changed; not what to
    assertEquals(2, shown.get("legacy").get("new").asInt()); // unknown to the definition: shown as stored
  }

  @Test
  void showsThemToReadersWithThePermission() throws Exception {
    var item = AuditHistory.view(List.of(record(1L, changes())), TestWidget.DEF, holding("widget.cost.read"),
        Map.of()).getFirst();
    assertEquals("2.00", ((ObjectNode) item.get("changes")).get("cost").get("new").asText());
  }

  @Test
  void namesWhoActed() throws Exception {
    var users = Map.of(1L, new UserSummary(1, "U1", "Jane Roy"));
    var items = AuditHistory.view(List.of(record(1L, changes()), record(2L, changes()), record(null, changes())),
        TestWidget.DEF, FieldAccess.ALLOW_ALL, users);
    assertEquals(Map.of("id", 1L, "userNo", "U1", "name", "Jane Roy"), items.get(0).get("user"));
    @SuppressWarnings("unchecked")
    Map<String, Object> unknown = (Map<String, Object>) items.get(1).get("user");
    assertEquals(2L, unknown.get("id"));
    assertNull(unknown.get("name")); // a user the directory doesn't know: id only
    assertNull(items.get(2).get("user")); // nobody acted (a CLI command)
    assertEquals(List.of("id", "action", "occurredAt", "user", "requestId", "changes"),
        List.copyOf(items.get(0).keySet()));
  }

  @Test
  void labelsNameTheActionAndEveryChangedFieldMaskedOrNot() throws Exception {
    AuditHistory.Labels labels = new AuditHistory.Labels() {
      @Override
      public String field(String name) {
        return "<" + name + ">";
      }

      @Override
      public String action(String action) {
        return "[" + action + "]";
      }
    };

    var item = AuditHistory.view(List.of(record(1L, changes())), TestWidget.DEF, holding(), Map.of(), labels)
        .getFirst();

    assertEquals("[UPDATED]", item.get("actionLabel"));
    assertEquals(Map.of("name", "<name>", "cost", "<cost>", "legacy", "<legacy>"), item.get("labels"));
    // without labels: neither key
    var plain = AuditHistory.view(List.of(record(1L, changes())), TestWidget.DEF, holding(), Map.of()).getFirst();
    assertEquals(false, plain.containsKey("labels"));
  }
}

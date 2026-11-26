package com.lesconstructionssapete.stempyerpserver.platform.audit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.UserDirectory.UserSummary;

/**
 * Turns audit records into what a history response shows a given reader.
 * Pure.
 *
 * A change to a field the reader may not read (its readPermission, or an
 * internal field) is shown as {"masked": true}: the reader learns THAT it
 * changed, not its values. A field the definition no longer has (renamed,
 * removed) is shown as stored.
 */
public final class AuditHistory {

  private static final JsonNode MASKED = JsonNodeFactory.instance.objectNode().put("masked", true);

  /**
   * Names fields and actions for people, in the reader's language
   * (docs/ARCHITECTURE.md §23.5).
   */
  public interface Labels {
    String field(String name);

    String action(String action);
  }

  private AuditHistory() {
  }

  /** One response item per record, nothing masked: for histories whose fields have no read permissions. */
  public static List<Map<String, Object>> view(List<AuditRecord> records, Map<Long, UserSummary> users) {
    return view(records, null, FieldAccess.ALLOW_ALL, users);
  }

  /**
   * One response item per record, in the records' order.
   *
   * @param entity null: no field is masked
   */
  public static List<Map<String, Object>> view(List<AuditRecord> records, EntityDefinition<?> entity,
      FieldAccess access, Map<Long, UserSummary> users) {
    return view(records, entity, access, users, null);
  }

  /**
   * As above, each item also carrying "actionLabel" and "labels" (field name
   * to label, for the fields in its changes).
   *
   * @param labels null: no labels
   */
  public static List<Map<String, Object>> view(List<AuditRecord> records, EntityDefinition<?> entity,
      FieldAccess access, Map<Long, UserSummary> users, Labels labels) {
    List<Map<String, Object>> items = new ArrayList<>(records.size());
    for (AuditRecord r : records) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", r.id());
      item.put("action", r.action());
      item.put("occurredAt", r.occurredAt());
      item.put("user", user(r.userId(), users));
      item.put("requestId", r.requestId());
      item.put("changes", masked(r.changes(), entity, access));
      if (labels != null) {
        item.put("actionLabel", labels.action(r.action()));
        Map<String, String> names = new LinkedHashMap<>();
        r.changes().fieldNames().forEachRemaining(f -> names.put(f, labels.field(f)));
        item.put("labels", names);
      }
      items.add(item);
    }
    return items;
  }

  /** Null when nobody acted (a CLI command); id only if the user can't be found. */
  private static Map<String, Object> user(Long id, Map<Long, UserSummary> users) {
    if (id == null) {
      return null;
    }
    Map<String, Object> user = new LinkedHashMap<>();
    UserSummary s = users.get(id);
    user.put("id", id);
    user.put("userNo", s == null ? null : s.userNo());
    user.put("name", s == null ? null : s.name());
    return user;
  }

  private static ObjectNode masked(ObjectNode changes, EntityDefinition<?> entity, FieldAccess access) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    Iterator<Map.Entry<String, JsonNode>> it = changes.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      Optional<? extends Field<?, ?>> field = entity == null ? Optional.empty() : entity.field(e.getKey());
      boolean hidden = field.isPresent() && (field.get().isInternal() || !access.canRead(field.get()));
      out.set(e.getKey(), hidden ? MASKED.deepCopy() : e.getValue());
    }
    return out;
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * One audited change, as AuditLog writes it.
 *
 * @param entity    what changed: EntityDefinition.name() ("product"), or a
 *                  stable name for hand-written writes ("schedule", "user")
 * @param entityId  the changed row's id
 * @param companyId the company the row belongs to; null for install-wide data
 * @param action    {@link #CREATED}, {@link #UPDATED}, {@link #DELETED}, or a
 *                  specific action in UPPER_SNAKE_CASE ("ROLE_ASSIGNED")
 * @param userId    who acted (users.id); null when nobody did (a CLI command)
 * @param requestId RequestContext.requestId; null when there is none
 * @param occurredAt when the change happened (the write's time)
 * @param changes   API field name -> old and new value, only what changed; may
 *                  be empty (a delete, a password change: never its value)
 */
public record AuditEntry(String entity, long entityId, Long companyId, String action, Long userId, String requestId,
    Instant occurredAt, Map<String, FieldChange> changes) {

  public static final String CREATED = "CREATED";
  public static final String UPDATED = "UPDATED";
  public static final String DELETED = "DELETED";

  public AuditEntry {
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (entity.isBlank() || entity.length() > 100) {
      throw new IllegalArgumentException("entity must be 1-100 characters");
    }
    if (!action.matches("[A-Z][A-Z0-9_]{0,49}")) {
      throw new IllegalArgumentException("action must be UPPER_SNAKE_CASE, at most 50 characters: " + action);
    }
    // Keeps field order and allows null values (Map.copyOf would refuse them).
    changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes == null ? Map.of() : changes));
  }
}

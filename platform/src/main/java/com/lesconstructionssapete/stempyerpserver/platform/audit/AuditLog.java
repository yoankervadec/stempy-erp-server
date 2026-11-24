package com.lesconstructionssapete.stempyerpserver.platform.audit;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.Timestamps;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * The audit trail's only writer and reader (docs/ARCHITECTURE.md §20).
 * Published in the ServiceRegistry.
 *
 * {@link #record} writes in the CALLER's transaction: the entry commits or
 * rolls back with the change it describes, and if it can't be written the
 * change fails too. Generic writes are recorded by the AuditTrail listener;
 * hand-written writes that must be audited (roles, passwords, schedules) call
 * record() themselves, from the operation that makes the change.
 *
 * Changes are stored as JSON with the application's mapper (JsonMapperFactory):
 * {"field": {"old": ..., "new": ...}}, decimals and dates as strings.
 */
public final class AuditLog {

  /** Needed, on top of a record's own read permission, to see its history. Declared by ModuleHost. */
  public static final String READ_PERMISSION = "audit.history.read";

  private static final String COLUMNS = """
      id, company_id, entity, entity_id, action, user_id, request_id, occurred_at, changes""";

  private final ObjectMapper json;

  public AuditLog(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  public void record(UnitOfWork uow, AuditEntry entry) {
    Objects.requireNonNull(entry, "entry");
    Jdbc.insert(uow, """
        INSERT INTO audit_log (company_id, entity, entity_id, action, user_id, request_id, changes, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        entry.companyId(), entry.entity(), entry.entityId(), entry.action(), entry.userId(),
        truncate(entry.requestId(), 100), changesJson(entry), entry.occurredAt());
  }

  /**
   * A record's entries in one company, newest first. Scoping by company keeps
   * another company's history of the same id out of sight, and still finds
   * the history of a deleted row.
   *
   * @param companyId null for install-wide data
   */
  public List<AuditRecord> history(UnitOfWork uow, String entity, long entityId, Long companyId, int limit,
      int offset) {
    return Jdbc.queryList(uow, "SELECT " + COLUMNS + " FROM audit_log WHERE entity = ? AND entity_id = ?"
        + " AND company_id <=> ? ORDER BY id DESC LIMIT ? OFFSET ?",
        rs -> new AuditRecord(
            rs.getLong("id"),
            rs.getObject("company_id", Long.class),
            rs.getString("entity"),
            rs.getLong("entity_id"),
            rs.getString("action"),
            rs.getObject("user_id", Long.class),
            rs.getString("request_id"),
            Timestamps.read(rs, "occurred_at"),
            parse(rs.getString("changes"))),
        entity, entityId, companyId, limit, offset);
  }

  /** Deletes up to {@code limit} entries that occurred before {@code cutoff}; returns how many. */
  public int deleteBefore(UnitOfWork uow, Instant cutoff, int limit) {
    return Jdbc.update(uow, "DELETE FROM audit_log WHERE occurred_at < ? ORDER BY id LIMIT ?", cutoff, limit);
  }

  // ---- JSON ----

  /** NULL when the action carries no values. */
  private String changesJson(AuditEntry entry) {
    if (entry.changes().isEmpty()) {
      return null;
    }
    ObjectNode root = json.createObjectNode();
    entry.changes().forEach((field, change) -> {
      ObjectNode node = root.putObject(field);
      node.set("old", json.valueToTree(change.oldValue()));
      node.set("new", json.valueToTree(change.newValue()));
    });
    try {
      return json.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ObjectNode parse(String changes) {
    if (changes == null) {
      return json.createObjectNode();
    }
    try {
      JsonNode node = json.readTree(changes);
      return node instanceof ObjectNode o ? o : json.createObjectNode();
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}

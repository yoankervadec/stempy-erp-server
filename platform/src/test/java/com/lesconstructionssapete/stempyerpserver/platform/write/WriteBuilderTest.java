package com.lesconstructionssapete.stempyerpserver.platform.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.query.Bind;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestPart;

class WriteBuilderTest {

  private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
  private static final WriteContext CTX = new WriteContext(42, 7, NOW);
  private static final FieldAccess NO_NOTE = FieldAccess.forPermissions(p -> false);

  private final WriteBuilder builder = new WriteBuilder();

  private static List<Object> values(List<Bind> binds) {
    return binds.stream().map(Bind::value).toList();
  }

  private static Map<String, String> errors(ValidationFailedException e) {
    return e.fieldErrors().stream().collect(java.util.stream.Collectors.toMap(FieldError::field, FieldError::code));
  }

  // ---- insert ----

  @Test
  void insertAddsCompanyAndAuditColumnsFromTheServer() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", "P1");
    body.put("name", "Bolt");
    SQLWrite<TestPart> w = builder.insert(TestPart.DEF, body, CTX, FieldAccess.ALLOW_ALL);

    assertEquals("INSERT INTO parts (code, name, company_id, created_at, created_by, updated_at, updated_by)"
        + " VALUES (?, ?, ?, ?, ?, ?, ?)", w.sql());
    assertEquals(List.of("P1", "Bolt", 42L, NOW, 7L, NOW, 7L), values(w.binds()));
  }

  @Test
  void insertReportsEveryProblemAtOnce() {
    var e = assertThrows(ValidationFailedException.class, () -> builder.insert(TestPart.DEF,
        Map.of("createdBy", 1, "companyId", 9, "nope", 1, "note", "x", "code", "TOO-LONG-CODE"), CTX, NO_NOTE));

    assertEquals(Map.of(
        "createdBy", "READ_ONLY", // server-managed
        "companyId", "UNKNOWN_FIELD", // internal: not even visible
        "nope", "UNKNOWN_FIELD",
        "note", "FORBIDDEN", // needs part.note.write
        "code", "TOO_LONG",
        "name", "REQUIRED"), errors(e));
  }

  // ---- update ----

  @Test
  void updateIsVersionGuardedCompanyScopedAndStampsTheAuditColumns() {
    SQLWrite<TestPart> w = builder.update(TestPart.DEF, 5, 3L, Map.of("name", "Nut"), CTX, FieldAccess.ALLOW_ALL);

    assertEquals("UPDATE parts SET name = ?, updated_at = ?, updated_by = ?, version = version + 1"
        + " WHERE id = ? AND company_id = ? AND deleted_at IS NULL AND version = ?", w.sql());
    assertEquals(List.of("Nut", NOW, 7L, 5L, 42L, 3L), values(w.binds()));
    assertEquals("SELECT 1 FROM parts WHERE id = ? AND company_id = ? AND deleted_at IS NULL", w.existsSql());
    // The old values of the changed fields, locked until commit (no version guard: read whatever is there).
    assertEquals("SELECT name FROM parts WHERE id = ? AND company_id = ? AND deleted_at IS NULL FOR UPDATE",
        w.beforeSql());
    assertEquals(List.of(5L, 42L), values(w.beforeBinds()));
  }

  @Test
  void updateNeedsTheVersionAndRefusesImmutableFields() {
    var missing = assertThrows(ValidationFailedException.class,
        () -> builder.update(TestPart.DEF, 5, null, Map.of("name", "Nut"), CTX, FieldAccess.ALLOW_ALL));
    assertEquals(Map.of("version", "REQUIRED"), errors(missing));

    var immutable = assertThrows(ValidationFailedException.class,
        () -> builder.update(TestPart.DEF, 5, 3L, Map.of("code", "P2"), CTX, FieldAccess.ALLOW_ALL));
    assertEquals(Map.of("code", "READ_ONLY"), errors(immutable));
  }

  // ---- soft delete ----

  @Test
  void softDeleteSetsDeletedAtUnderTheSameGuard() {
    SQLWrite<TestPart> w = builder.softDelete(TestPart.DEF, 5, 3L, CTX);

    assertEquals("UPDATE parts SET deleted_at = ?, updated_at = ?, updated_by = ?, version = version + 1"
        + " WHERE id = ? AND company_id = ? AND deleted_at IS NULL AND version = ?", w.sql());
    assertEquals(List.of(NOW, NOW, 7L, 5L, 42L, 3L), values(w.binds()));
    assertEquals(null, w.beforeSql()); // no client field changes: nothing to read before
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;

class QueryTranslatorTest {

  private final QuerySpecParser parser = new QuerySpecParser(QueryLimits.defaults());
  private final QueryTranslator translator = new QueryTranslator(QueryLimits.defaults());

  @Test
  void simpleFilterIsScopedToCompanyAndUsesPlaceholders() {
    QuerySpec spec = parser.parse(Map.of(
        "where", Map.of("field", "name", "op", "equals", "value", "abc")));

    SQLQuery<TestWidget> q = translator.translate(TestWidget.DEF, spec, 42L, FieldAccess.ALLOW_ALL);

    assertEquals(
        "SELECT t0.id, t0.name, t0.unit_cost FROM widget t0 "
            + "WHERE t0.company_id = ? AND t0.name = ? "
            + "ORDER BY t0.id ASC LIMIT ? OFFSET ?",
        q.sql());
    // order matters: company, filter value, limit, offset (50 is the default page
    // size)
    assertEquals(List.of(42L, "abc", 50L, 0L), q.binds().stream().map(Bind::value).toList());
  }

  @Test
  void hostileValueIsBoundNeverConcatenated() {
    QuerySpec spec = parser.parse(Map.of(
        "where", Map.of("field", "name", "op", "equals", "value", "x'; DROP TABLE widget; --")));

    SQLQuery<TestWidget> q = translator.translate(TestWidget.DEF, spec, 42L, FieldAccess.ALLOW_ALL);

    assertFalse(q.sql().contains("DROP")); // not in the SQL text
    assertTrue(q.binds().stream().anyMatch(b -> b.value().equals("x'; DROP TABLE widget; --")));
  }

  @Test
  void unknownFieldIsRejected() {
    QuerySpec spec = parser.parse(Map.of(
        "where", Map.of("field", "password", "op", "equals", "value", "x")));

    ValidationFailedException e = assertThrows(ValidationFailedException.class,
        () -> translator.translate(TestWidget.DEF, spec, 42L, FieldAccess.ALLOW_ALL));

    assertEquals("UNKNOWN_FIELD", e.fieldErrors().get(0).code());
  }

  @Test
  void userWithoutPermissionCannotFilterOnProtectedField() {
    QuerySpec spec = parser.parse(Map.of(
        "where", Map.of("field", "cost", "op", "greaterThan", "value", 1)));

    FieldAccess noPermissions = FieldAccess.forPermissions(p -> false);

    ValidationFailedException e = assertThrows(ValidationFailedException.class,
        () -> translator.translate(TestWidget.DEF, spec, 42L, noPermissions));
    assertEquals("UNKNOWN_FIELD", e.fieldErrors().get(0).code()); // looks the same as a field that doesn't exist
  }

  @Test
  void userWithPermissionCanFilterOnProtectedField() {
    QuerySpec spec = parser.parse(Map.of(
        "where", Map.of("field", "cost", "op", "greaterThan", "value", 1)));

    FieldAccess hasCost = FieldAccess.forPermissions("widget.cost.read"::equals);

    SQLQuery<TestWidget> q = translator.translate(TestWidget.DEF, spec, 42L, hasCost);
    assertTrue(q.sql().contains("t0.unit_cost > ?"));
  }

  @Test
  void parserRejectsUnknownOperator() {
    ValidationFailedException e = assertThrows(ValidationFailedException.class,
        () -> parser.parse(Map.of("where", Map.of("field", "name", "op", "nope", "value", "x"))));

    assertEquals("UNKNOWN_OPERATOR", e.fieldErrors().get(0).code());
  }
}

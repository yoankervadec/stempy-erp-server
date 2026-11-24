package com.lesconstructionssapete.stempyerpserver.modules.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditRecord;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;

/**
 * The hand-written security writes are audited: role assignments,
 * memberships and password changes, only when something changes, and never
 * with a password or its hash.
 */
class SecurityAuditMySqlTest {

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static AuditLog audit;
  private static UsersApi users;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(SecurityAuditMySqlTest.class, AuthSettings.defaults(), SecurityAuditMySqlTest::seed);
    uow = new UnitOfWorkFactory(stack.dataSource());
    audit = stack.services().get(AuditLog.class);
    users = stack.users();
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE), (2, 'OTHER', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Member', 1, 1), (2, NULL, 'U2', 'Newcomer', 1, 1), (3, 1, 'U3', 'Disabled member', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id, is_enabled) VALUES (1, 1, TRUE), (3, 1, FALSE)");
    st.execute("INSERT INTO roles (id, code, name) VALUES (10, 'EDITOR', 'Editor'), (11, 'READER', 'Reader')");
    st.execute("INSERT INTO user_company_roles (user_id, company_id, role_id) VALUES (1, 1, 11), (3, 1, 11)");
  }

  /** Newest first. */
  private static List<AuditRecord> history(long userId, Long companyId) {
    return uow.executeReadOnly(u -> audit.history(u, "user", userId, companyId, 100, 0));
  }

  private static List<String> actions(long userId, Long companyId) {
    return history(userId, companyId).stream().map(AuditRecord::action).toList();
  }

  @Test
  void aNewRoleIsAuditedOnceAndARepeatIsNot() {
    users.assignRole("U1", "ACME", "EDITOR"); // already a member: only the role is new
    AuditRecord role = history(1, 1L).getFirst();
    assertEquals("ROLE_ASSIGNED", role.action());
    assertEquals(1L, role.companyId());
    assertNull(role.userId()); // from the command line: nobody acting
    assertTrue(role.changes().get("role").get("old").isNull());
    assertEquals("EDITOR", role.changes().get("role").get("new").asText());

    users.assignRole("U1", "ACME", "EDITOR");
    assertEquals(List.of("ROLE_ASSIGNED"), actions(1, 1L)); // nothing changed, nothing recorded
  }

  @Test
  void aNewMembershipIsAuditedInItsCompany() {
    users.assignRole("U2", "OTHER", "READER");
    assertEquals(List.of("ROLE_ASSIGNED", "MEMBERSHIP_GRANTED"), actions(2, 2L));
    AuditRecord membership = history(2, 2L).get(1);
    assertTrue(membership.changes().get("enabled").get("old").isNull()); // there was none
    assertTrue(membership.changes().get("enabled").get("new").asBoolean());
    assertEquals(List.of(), actions(2, 1L)); // nothing in ACME
  }

  @Test
  void reEnablingAMembershipIsAudited() {
    users.assignRole("U3", "ACME", "READER"); // has the role; the membership was disabled
    assertEquals(List.of("MEMBERSHIP_GRANTED"), actions(3, 1L));
    AuditRecord membership = history(3, 1L).getFirst();
    assertEquals(false, membership.changes().get("enabled").get("old").asBoolean());
  }

  @Test
  void aPasswordChangeIsAuditedWithoutAnyValue() {
    users.setPassword("U1", "correct horse battery".toCharArray());

    AuditRecord change = history(1, null).getFirst(); // install-wide: a user's password is not per company
    assertEquals("PASSWORD_CHANGED", change.action());
    assertNull(change.companyId());
    assertEquals(0, change.changes().size());
    String hash = uow.executeReadOnly(u -> Jdbc.queryFirst(u,
        "SELECT password_hash FROM user_credentials WHERE user_id = 1 AND deleted_at IS NULL", rs -> rs.getString(1)))
        .orElseThrow();
    assertEquals(0, (int) uow.executeReadOnly(u -> Jdbc.queryFirst(u,
        "SELECT COUNT(*) FROM audit_log WHERE changes IS NOT NULL AND CAST(changes AS CHAR) LIKE ?",
        rs -> rs.getInt(1), "%" + hash.substring(0, 20) + "%")).orElseThrow());
  }
}

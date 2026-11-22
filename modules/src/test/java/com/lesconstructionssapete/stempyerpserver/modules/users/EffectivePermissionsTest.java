package com.lesconstructionssapete.stempyerpserver.modules.users;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository.Overrides;
import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository.RoleGrants;

class EffectivePermissionsTest {

  private static final Set<String> DECLARED = Set.of("a.b.read", "a.b.write", "a.c.read");
  private static final Overrides NONE = new Overrides(Set.of(), Set.of());

  @Test
  void rolesAreUnioned() {
    var roles = new RoleGrants(false, Set.of("a.b.read", "a.c.read"));
    assertEquals(Set.of("a.b.read", "a.c.read"), EffectivePermissions.compute(roles, NONE, DECLARED));
  }

  @Test
  void allPermissionsMeansEveryDeclaredOne() {
    var roles = new RoleGrants(true, Set.of());
    assertEquals(DECLARED, EffectivePermissions.compute(roles, NONE, DECLARED));
  }

  @Test
  void grantAddsAndDenyBeatsEverythingIncludingAllPermissions() {
    var overrides = new Overrides(Set.of("a.c.read"), Set.of("a.b.write"));

    assertEquals(Set.of("a.b.read", "a.c.read"),
        EffectivePermissions.compute(new RoleGrants(false, Set.of("a.b.read", "a.b.write")), overrides, DECLARED));
    assertEquals(Set.of("a.b.read", "a.c.read"),
        EffectivePermissions.compute(new RoleGrants(true, Set.of()), overrides, DECLARED));
    // denied by override even when also granted by override
    assertEquals(Set.of(), EffectivePermissions.compute(new RoleGrants(false, Set.of()),
        new Overrides(Set.of("a.b.write"), Set.of("a.b.write")), DECLARED));
  }

  @Test
  void undeclaredNamesAreDroppedAndReported() {
    var roles = new RoleGrants(false, Set.of("a.b.read", "gone.old.read"));
    var overrides = new Overrides(Set.of("gone.granted.read"), Set.of("gone.denied.read"));

    assertEquals(Set.of("a.b.read"), EffectivePermissions.compute(roles, overrides, DECLARED));
    assertEquals(Set.of("gone.old.read", "gone.granted.read", "gone.denied.read"),
        EffectivePermissions.undeclared(roles, overrides, DECLARED));
  }
}

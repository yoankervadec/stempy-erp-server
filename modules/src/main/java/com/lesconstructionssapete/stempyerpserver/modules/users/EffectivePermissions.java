package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.util.HashSet;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository.Overrides;
import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository.RoleGrants;

/**
 * The permission formula, for one user in one company. Pure: no database.
 *
 * <pre>
 * granted   = permissions of the user's roles (every declared one if a role has all_permissions)
 *           + GRANT overrides
 * effective = granted - DENY overrides            DENY beats everything, admins included
 *             restricted to declared permissions  stale names from old releases are ignored
 * </pre>
 */
public final class EffectivePermissions {

  private EffectivePermissions() {
  }

  public static Set<String> compute(RoleGrants roles, Overrides overrides, Set<String> declared) {
    Set<String> result = new HashSet<>(roles.allPermissions() ? declared : roles.permissions());
    result.addAll(overrides.granted());
    result.removeAll(overrides.denied());
    result.retainAll(declared);
    return Set.copyOf(result);
  }

  /** Stored names the code no longer declares (to warn about). */
  public static Set<String> undeclared(RoleGrants roles, Overrides overrides, Set<String> declared) {
    Set<String> stored = new HashSet<>(roles.permissions());
    stored.addAll(overrides.granted());
    stored.addAll(overrides.denied());
    stored.removeAll(declared);
    return stored;
  }
}

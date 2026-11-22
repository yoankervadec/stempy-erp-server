package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.error.ForbiddenException;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * THE permission choke point: every check on a route or orchestrator goes
 * through here. Published in the ServiceRegistry by ModuleHost.
 *
 * The request's permission set is already final (roles, all-permissions roles
 * and per-user overrides are resolved when it is loaded), so a check is a set
 * lookup.
 */
public final class PermissionChecker {

  private final PermissionRegistry registry;

  public PermissionChecker(PermissionRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** 403 PERMISSION_DENIED unless the caller holds {@code permission}. */
  public void require(RequestContext rc, String permission) {
    if (!has(rc, permission)) {
      throw ForbiddenException.missingPermission(permission);
    }
  }

  public boolean has(RequestContext rc, String permission) {
    requireDeclared(permission);
    return rc.hasPermission(permission);
  }

  /**
   * Fails on a permission no module declared: a typo would otherwise deny
   * everyone, silently. Call it at route registration to fail at startup.
   */
  public void requireDeclared(String permission) {
    if (!registry.contains(permission)) {
      throw new IllegalStateException("Permission not declared by any module: " + permission);
    }
  }
}

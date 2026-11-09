package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Objects;
import java.util.Set;

/**
 * Who is calling, for which company, and which request this is. Built once per
 * request by the auth filter, then passed explicitly to orchestrators (never
 * stashed in a ThreadLocal).
 *
 * Immutable and cheap to copy, so scheduler jobs can build their own with a
 * system user id.
 *
 * @param requestId   correlation id for logs and error responses
 * @param userId      the authenticated user (users.id), also used for
 *                    created_by / updated_by
 * @param companyId   the company this request operates on; every repository
 *                    query filters on it
 * @param permissions permission names granted to this user for this company
 */
public record RequestContext(String requestId, long userId, long companyId, Set<String> permissions) {

  public RequestContext {
    Objects.requireNonNull(requestId, "requestId");
    permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
  }

  /**
   * Cheap read-only check. For enforcement, call PermissionChecker (the single
   * choke point),
   * so rules like "admins can do everything" live in one place instead of every
   * caller.
   */
  public boolean hasPermission(String permission) {
    return permissions.contains(permission);
  }
}

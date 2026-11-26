package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Objects;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;

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
 * @param language    what the server writes texts in for this request
 *                    (labels, translated errors): the user's own language,
 *                    else the company's (docs/ARCHITECTURE.md §23.5)
 */
public record RequestContext(String requestId, long userId, long companyId, Set<String> permissions,
    Language language) {

  public RequestContext {
    Objects.requireNonNull(requestId, "requestId");
    permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
    Objects.requireNonNull(language, "language");
  }

  /** In the default language: for jobs, event deliveries and tests. */
  public RequestContext(String requestId, long userId, long companyId, Set<String> permissions) {
    this(requestId, userId, companyId, permissions, Language.DEFAULT);
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

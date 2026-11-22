package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every permission the code knows about. Modules declare theirs in
 * ERPModule.registerPermissions; the code is the single source of truth, and
 * grants stored in the database are matched against it by name.
 *
 * Names are lowercase dot-separated segments, camelCase inside a segment, at
 * least three segments:
 *
 * <pre>
 * &lt;module&gt;.&lt;resource&gt;.&lt;action&gt;                  masterdata.product.read, sales.order.post
 * &lt;module&gt;.&lt;resource&gt;.field.&lt;field&gt;.read|write  masterdata.product.field.unitAmount.read
 * </pre>
 *
 * Never rename a permission casually: grants are stored by name, so a rename
 * silently revokes it everywhere. Rename through a Flyway data migration.
 *
 * Lifecycle: declare at startup, freeze(), then read-only and thread-safe.
 */
public final class PermissionRegistry {

  private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*){2,}");

  /** @param description shown to administrators, e.g. "View products" */
  public record Permission(String name, String description) {
    public Permission {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(description, "description");
    }
  }

  private final Map<String, Permission> byName = new LinkedHashMap<>();
  private volatile boolean frozen;

  public PermissionRegistry declare(String name, String description) {
    if (frozen) {
      throw new IllegalStateException("PermissionRegistry is frozen; declare permissions in registerPermissions()");
    }
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid permission name '" + name
          + "': expected lowercase dot-separated segments like masterdata.product.read");
    }
    if (byName.putIfAbsent(name, new Permission(name, description)) != null) {
      throw new IllegalStateException("Permission declared twice: " + name);
    }
    return this;
  }

  public void freeze() {
    frozen = true;
  }

  public boolean contains(String name) {
    return byName.containsKey(name);
  }

  public Collection<Permission> all() {
    return Collections.unmodifiableCollection(byName.values());
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(byName.keySet());
  }
}

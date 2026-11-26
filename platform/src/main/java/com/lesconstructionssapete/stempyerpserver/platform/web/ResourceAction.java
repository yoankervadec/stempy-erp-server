package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Something a resource does besides create / update / delete
 * (docs/ARCHITECTURE.md §23.9): reverse an entry, close a period. Described in
 * GET /meta (name, label, method, path, parameters, confirmation) so the
 * client shows a button and builds its dialog; the module registers the
 * route itself, which checks everything again.
 *
 * <pre>
 * ResourceAction.onRecord("reverse").post("/reverse").permission("gl.journal.reverse")
 *     .param(ActionParam.date("postingDate").required())
 *     .param(ActionParam.string("description").maxLength(255))
 *     .confirm()
 *     .when(row -> row.get("reversedById") == null)
 * </pre>
 *
 * Labels (LabelKeys): "{resource}.action.{name}", the confirmation
 * "{resource}.action.{name}.confirm" (its {placeholders} filled from the
 * record's fields), each parameter "{resource}.action.{name}.param.{param}",
 * else the field label of the same name. ModuleHost refuses to start when one
 * is missing in a language.
 */
public final class ResourceAction {

  /** On one record ({path}/{id}{suffix}), or on the resource ({path}{suffix}: "create a fiscal year"). */
  public enum Scope {
    RECORD, RESOURCE
  }

  private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final String name;
  private final Scope scope;
  private String method = "POST";
  private String suffix;
  private String permission;
  private boolean confirm;
  private final List<ActionParam> params = new ArrayList<>();
  private Predicate<Map<String, Object>> when = row -> true;

  private ResourceAction(String name, Scope scope) {
    if (name == null || !name.matches("[a-z][A-Za-z0-9]*")) {
      throw new IllegalArgumentException("Invalid action name: " + name);
    }
    this.name = name;
    this.scope = scope;
  }

  public static ResourceAction onRecord(String name) {
    return new ResourceAction(name, Scope.RECORD);
  }

  public static ResourceAction onResource(String name) {
    return new ResourceAction(name, Scope.RESOURCE);
  }

  public ResourceAction post(String suffix) {
    return route("POST", suffix);
  }

  /** The route: method and the path after the resource's (and the id, on a record). */
  public ResourceAction route(String method, String suffix) {
    if (!METHODS.contains(method)) {
      throw new IllegalArgumentException(name + ": method must be one of " + METHODS);
    }
    if (suffix == null || !suffix.matches("(/[a-z][a-z0-9-]*)+")) {
      throw new IllegalArgumentException(name + ": path suffix like \"/reverse\" expected, got " + suffix);
    }
    this.method = method;
    this.suffix = suffix;
    return this;
  }

  public ResourceAction permission(String permission) {
    this.permission = Objects.requireNonNull(permission, "permission");
    return this;
  }

  public ResourceAction param(ActionParam param) {
    if (params.stream().anyMatch(p -> p.name().equals(param.name()))) {
      throw new IllegalArgumentException(name + ": parameter " + param.name() + " declared twice");
    }
    params.add(param);
    return this;
  }

  /** The client asks the user to confirm first (text: "{resource}.action.{name}.confirm"). */
  public ResourceAction confirm() {
    this.confirm = true;
    return this;
  }

  /** A record action applies to a record only when this holds for its row (state, not permission). */
  public ResourceAction when(Predicate<Map<String, Object>> condition) {
    if (scope != Scope.RECORD) {
      throw new IllegalStateException(name + ": when() is for record actions");
    }
    this.when = Objects.requireNonNull(condition, "condition");
    return this;
  }

  /** Fails on a declaration missing its route or permission. */
  void check() {
    if (suffix == null) {
      throw new IllegalStateException("Action " + name + " has no route (post(\"/...\"))");
    }
    if (permission == null) {
      throw new IllegalStateException("Action " + name + " has no permission");
    }
  }

  public String name() {
    return name;
  }

  public Scope scope() {
    return scope;
  }

  public String method() {
    return method;
  }

  public String suffix() {
    return suffix;
  }

  public String permission() {
    return permission;
  }

  public boolean needsConfirmation() {
    return confirm;
  }

  public List<ActionParam> params() {
    return List.copyOf(params);
  }

  public boolean appliesTo(Map<String, Object> row) {
    return when.test(row);
  }
}

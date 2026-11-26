package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Operator;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.web.ResourceCatalog.Resource;

import io.javalin.http.Context;

/**
 * GET /meta (docs/ARCHITECTURE.md §23.6): what the caller may see and do, for
 * the request's company, so the client can build its menu, lists and forms
 * without knowing the permission rules.
 *
 * <pre>
 * { "apiVersion": 1,
 *   "menu": [{ "module": "masterdata.items", "resources": ["product", "productCategory"] }],
 *   "resources": [{ "key": "product", "module": "masterdata.items", "path": "/masterdata/products",
 *     "can": { "read", "create", "update", "delete", "history" }, "idField": "id", "versionField": "version",
 *     "title": "productNo", "subtitle": "name", "listColumns": [...], "defaultSort": [{ "field", "dir" }],
 *     "presentation": "modal",
 *     "fields": [{ "name", "type", "ref"?, "values"?, "required", "unique", "maxLength"?, "min"?, "max"?,
 *                  "scale"? (decimals), "format"?, "currencyField"?,
 *                  "filterable", "sortable", "operators"?, "writable": { "create", "update" } }] }] }
 * </pre>
 *
 * Only resources the caller can read are listed, and only fields they can
 * read. A field is writable on create (update) when the caller can create
 * (update) the resource, the field is creatable (updatable) and its write
 * permission, if any, is held. A many-to-one foreign key is a "reference" to
 * the target's resource when the caller can read that resource, a plain
 * integer otherwise.
 *
 * The UI hints (§23.4) follow the same rule: a title, subtitle, column, sort
 * key or currency field the caller can't read is left out (the title falls
 * back to the id, the order to id descending).
 *
 * Answers with an ETag (a hash of the body): a client sending it back in
 * If-None-Match gets 304 until the answer changes. The metadata only
 * describes; every route still checks for itself.
 */
public final class MetaRoutes {

  public static final String PATH = "/meta";
  /** Bumped when a change to this body would break an existing client. */
  public static final int API_VERSION = 1;
  /** Decimals shown for a decimal field without a declared scale. */
  public static final int DEFAULT_SCALE = 2;

  private final ResourceCatalog catalog;
  private final PermissionChecker permissions;
  private final ObjectMapper json = JsonMapperFactory.newObjectMapper();

  public MetaRoutes(ResourceCatalog catalog, PermissionChecker permissions) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    permissions.requireDeclared(AuditLog.READ_PERMISSION);
  }

  public void register(Routes routes) {
    routes.get(PATH, this::handle);
  }

  private void handle(Context ctx) throws JsonProcessingException {
    byte[] body = json.writeValueAsBytes(describe(RequestContexts.require(ctx)));
    String etag = etag(body);
    ctx.header("ETag", etag);
    ctx.header("Cache-Control", "private, no-cache");
    if (etag.equals(ctx.header("If-None-Match"))) {
      ctx.status(304);
      return;
    }
    ctx.contentType("application/json").result(body);
  }

  /** The body of GET /meta for this caller. */
  public Map<String, Object> describe(RequestContext rc) {
    Map<String, List<String>> menu = new LinkedHashMap<>();
    List<Map<String, Object>> resources = new ArrayList<>();
    FieldAccess access = FieldAccess.forPermissions(rc::hasPermission);
    for (Resource r : catalog.all()) {
      if (!can(rc, r, Action.READ)) {
        continue;
      }
      menu.computeIfAbsent(r.module(), m -> new ArrayList<>()).add(r.key());
      resources.add(resource(rc, r, access));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("apiVersion", API_VERSION);
    out.put("menu", menu.entrySet().stream()
        .map(e -> pair("module", e.getKey(), "resources", e.getValue())).toList());
    out.put("resources", resources);
    return out;
  }

  private Map<String, Object> resource(RequestContext rc, Resource r, FieldAccess access) {
    boolean create = can(rc, r, Action.CREATE);
    boolean update = can(rc, r, Action.UPDATE);
    Map<String, Object> can = new LinkedHashMap<>();
    can.put("read", true);
    can.put("create", create);
    can.put("update", update);
    can.put("delete", can(rc, r, Action.DELETE));
    can.put("history", permissions.has(rc, AuditLog.READ_PERMISSION));

    EntityDefinition<?> entity = r.entity();
    List<? extends Field<?, ?>> readable = entity.readableFields(access);
    List<Map<String, Object>> fields = new ArrayList<>();
    for (Field<?, ?> f : readable) {
      fields.add(field(rc, entity, f, access, create, update));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("key", r.key());
    out.put("module", r.module());
    out.put("path", r.path());
    out.put("can", can);
    out.put("idField", entity.id().name());
    out.put("versionField", entity.versionField() == null ? null : entity.versionField().name());
    Field<?, ?> title = entity.titleField();
    out.put("title", (readable.contains(title) ? title : entity.id()).name());
    out.put("subtitle", entity.subtitleField().filter(readable::contains).map(Field::name).orElse(null));
    out.put("listColumns", listColumns(entity, readable));
    out.put("defaultSort", defaultSort(entity, readable));
    out.put("presentation", entity.presentation().name().toLowerCase(Locale.ROOT));
    out.put("fields", fields);
    return out;
  }

  private Map<String, Object> field(RequestContext rc, EntityDefinition<?> entity, Field<?, ?> f,
      FieldAccess access, boolean create, boolean update) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", f.name());
    Optional<Resource> ref = reference(entity, f).filter(target -> can(rc, target, Action.READ));
    out.put("type", ref.isPresent() ? "reference" : f.type().name());
    ref.ifPresent(target -> out.put("ref", target.key()));
    Object[] constants = f.type().javaType().getEnumConstants();
    if (constants != null) {
      List<String> values = new ArrayList<>();
      for (Object c : constants) {
        values.add(((Enum<?>) c).name());
      }
      out.put("values", values);
    }
    out.put("required", f.isRequired());
    out.put("unique", f.isUnique());
    putIfSet(out, "maxLength", f.maxLength());
    putIfSet(out, "min", f.min());
    putIfSet(out, "max", f.max());
    if (f.type() == FieldType.DECIMAL) {
      out.put("scale", f.scale() == null ? DEFAULT_SCALE : f.scale());
    }
    if (f.format() != null) {
      out.put("format", f.format().name().toLowerCase(Locale.ROOT));
    }
    if (f.currencyField() != null && access.canRead(f.currencyField())) {
      out.put("currencyField", f.currencyField().name());
    }
    out.put("filterable", f.isFilterable());
    out.put("sortable", f.isSortable());
    if (f.isFilterable()) {
      out.put("operators", f.type().operators().stream().map(Operator::wire).toList());
    }
    boolean writable = access.canWrite(f);
    out.put("writable", pair(
        "create", create && writable && f.isCreatable(),
        "update", update && writable && f.isUpdatable()));
    return out;
  }

  private static List<String> listColumns(EntityDefinition<?> entity, List<? extends Field<?, ?>> readable) {
    List<String> declared = entity.listColumns().stream().filter(readable::contains).map(Field::name).toList();
    if (!declared.isEmpty()) {
      return declared;
    }
    return readable.stream().filter(f -> !f.isId() && !f.isVersion() && (f.isFilterable() || f.isSortable()))
        .map(Field::name).toList();
  }

  private static List<Map<String, Object>> defaultSort(EntityDefinition<?> entity,
      List<? extends Field<?, ?>> readable) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (SortKey k : entity.defaultSort()) {
      if (readable.contains(k.field())) {
        out.add(pair("field", k.field().name(), "dir", k.descending() ? "desc" : "asc"));
      }
    }
    if (out.isEmpty()) {
      out.add(pair("field", entity.id().name(), "dir", "desc"));
    }
    return out;
  }

  /**
   * The resource a field points at: the entity it declares with references(),
   * else the target of a many-to-one relation whose foreign key is this
   * field's column.
   */
  private Optional<Resource> reference(EntityDefinition<?> entity, Field<?, ?> f) {
    Optional<EntityDefinition<?>> declared = f.referencedEntity();
    if (declared.isPresent()) {
      return catalog.forEntity(declared.get());
    }
    for (Relation<?, ?> rel : entity.relations()) {
      if (rel.kind() == Relation.Kind.MANY_TO_ONE && rel.foreignKey().equals(f.column())) {
        return catalog.forEntity(rel.target());
      }
    }
    return Optional.empty();
  }

  private boolean can(RequestContext rc, Resource r, Action action) {
    return r.permission(action).map(p -> permissions.has(rc, p)).orElse(false);
  }

  /**
   * A two-key object in this key order. Every object in the body keeps a
   * fixed key order, so the same answer always hashes to the same ETag.
   */
  private static Map<String, Object> pair(String k1, Object v1, String k2, Object v2) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(k1, v1);
    out.put(k2, v2);
    return out;
  }

  private static void putIfSet(Map<String, Object> out, String key, Object value) {
    if (value != null) {
      out.put(key, value);
    }
  }

  private static String etag(byte[] body) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
      return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}

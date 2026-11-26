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
import com.lesconstructionssapete.stempyerpserver.platform.entity.Presentation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.LabelKeys;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.query.QueryTranslator;
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
 * { "apiVersion": 1, "language": "fr",
 *   "menu": [{ "module": "masterdata.items", "label", "resources": ["product", "productCategory"] }],
 *   "resources": [{ "key": "product", "label", "labelOne", "module": "masterdata.items", "path": "/masterdata/products",
 *     "can": { "read", "create", "update", "delete", "history" }, "idField": "id", "versionField": "version",
 *     "title": "productNo", "subtitle": "name", "listColumns": [...], "defaultSort": [{ "field", "dir" }],
 *     "presentation": "modal" | "page", "searchable": true (QUERY's "search" works),
 *     "layout": { "derived", "sections", "collections", "summary", "tabs" } (§23.7),
 *     "actions": [{ "name", "label", "scope": "record" | "resource", "method", "path", "confirm",
 *                   "params": [like fields] }],
 *     "fields": [{ "name", "label", "type", "ref"?, "values"? [{ "value", "label" }], "required", "unique", "maxLength"?, "min"?, "max"?,
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
 * Labels (§23.5) are in the request's language (RequestContext.language),
 * from the bundles' LabelKeys, else humanized.
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
  private final Messages messages;
  private final ObjectMapper json = JsonMapperFactory.newObjectMapper();

  public MetaRoutes(ResourceCatalog catalog, PermissionChecker permissions, Messages messages) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.messages = Objects.requireNonNull(messages, "messages");
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
    Language language = rc.language();
    List<Map<String, Object>> groups = new ArrayList<>();
    menu.forEach((module, keys) -> {
      Map<String, Object> group = new LinkedHashMap<>();
      group.put("module", module);
      group.put("label", messages.label(language, module, LabelKeys.module(module)));
      group.put("resources", keys);
      groups.add(group);
    });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("apiVersion", API_VERSION);
    out.put("language", language.code());
    out.put("menu", groups);
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
      fields.add(field(rc, r.key(), entity, f, access, create, update));
    }
    Language language = rc.language();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("key", r.key());
    out.put("label", messages.label(language, r.key(), LabelKeys.resource(r.key())));
    out.put("labelOne", messages.label(language, r.key(), LabelKeys.resourceOne(r.key()), LabelKeys.resource(r.key())));
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
    RecordLayout layout = r.layout() != null ? r.layout() : derivedLayout(entity);
    Map<String, Object> described = layout(rc, r, layout, access, create, update, (Boolean) can.get("history"));
    boolean tables = !((List<?>) described.get("collections")).isEmpty();
    out.put("presentation", entity.declaredPresentation().orElse(tables ? Presentation.PAGE : Presentation.MODAL)
        .name().toLowerCase(Locale.ROOT));
    out.put("searchable", !QueryTranslator.searchFields(entity, access).isEmpty());
    out.put("fields", fields);
    out.put("layout", described);
    out.put("actions", actions(rc, r));
    return out;
  }

  // ---- layouts (§23.7) ----

  /**
   * The layout as this caller sees it: fields they can't read are left out
   * (and a section left empty), a collection whose child resource they can't
   * read is left out, the history tab needs its permission.
   */
  private Map<String, Object> layout(RequestContext rc, Resource r, RecordLayout layout, FieldAccess access,
      boolean create, boolean update, boolean history) {
    Language language = rc.language();
    List<Map<String, Object>> sections = new ArrayList<>();
    for (RecordLayout.Section s : layout.sections()) {
      List<String> names = readableNames(s.fields(), access);
      if (names.isEmpty()) {
        continue;
      }
      Map<String, Object> section = new LinkedHashMap<>();
      section.put("name", s.name());
      section.put("label", messages.label(language, s.name(), LabelKeys.section(r.key(), s.name())));
      section.put("columns", s.columns());
      section.put("fields", names);
      sections.add(section);
    }
    List<Map<String, Object>> collections = new ArrayList<>();
    for (RecordLayout.Collection c : layout.collections()) {
      EntityDefinition<?> child = c.relation().target();
      Optional<Resource> childResource = catalog.forEntity(child);
      if (childResource.isPresent() && !can(rc, childResource.get(), Action.READ)) {
        continue;
      }
      List<Map<String, Object>> columns = new ArrayList<>();
      for (Field<?, ?> f : c.columns()) {
        if (!f.isInternal() && access.canRead(f)) {
          columns.add(field(rc, child.name(), child, f, access, c.editable() && create, c.editable() && update));
        }
      }
      Map<String, Object> collection = new LinkedHashMap<>();
      collection.put("name", c.name());
      collection.put("label", messages.label(language, c.name(),
          LabelKeys.collection(r.key(), c.name(), child.name())));
      collection.put("entity", child.name());
      collection.put("source", childResource.map(target -> source(target, c)).orElse(null));
      collection.put("editable", c.editable() && (create || update));
      collection.put("fixedRows", c.fixedRows());
      collection.put("columns", columns);
      collection.put("footerSums", readableNames(c.footerSums(), access));
      collections.add(collection);
    }
    List<Map<String, Object>> tabs = new ArrayList<>();
    for (String tab : layout.tabs()) {
      if (!tab.equals(RecordLayout.HISTORY) || history) {
        tabs.add(pair("name", tab, "label", messages.label(language, tab, LabelKeys.tab(tab))));
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("derived", r.layout() == null);
    out.put("sections", sections);
    out.put("collections", collections);
    out.put("summary", readableNames(layout.summary(), access));
    out.put("tabs", tabs);
    return out;
  }

  /**
   * Where a collection's rows come from when its child is a resource: QUERY
   * {resource path} where {field} equals the record's id. Otherwise null: the
   * rows come with the record (documents, §23.8).
   */
  private static Map<String, Object> source(Resource target, RecordLayout.Collection c) {
    String fk = c.relation().foreignKey();
    return target.entity().fields().stream().filter(f -> f.column().equals(fk) && !f.isInternal()).findFirst()
        .<Map<String, Object>>map(f -> pair("resource", target.key(), "field", f.name()))
        .orElse(null);
  }

  private static List<String> readableNames(List<Field<?, ?>> fields, FieldAccess access) {
    return fields.stream().filter(f -> !f.isInternal() && access.canRead(f)).map(Field::name).toList();
  }

  /**
   * The layout of an entity that declares none: its own fields ("general", in
   * declaration order), the server-filled ones ("record": created / updated
   * at and by), a read-only table per one-to-many relation (its target's list
   * columns), the history tab.
   */
  static RecordLayout derivedLayout(EntityDefinition<?> entity) {
    RecordLayout layout = RecordLayout.of(entity);
    List<Field<?, ?>> own = new ArrayList<>();
    List<Field<?, ?>> record = new ArrayList<>();
    List<String> audit = new ArrayList<>();
    for (String column : new String[] { entity.createdAtColumn(), entity.createdByColumn(),
        entity.updatedAtColumn(), entity.updatedByColumn() }) {
      if (column != null) {
        audit.add(column);
      }
    }
    for (Field<?, ?> f : entity.fields()) {
      if (f.isInternal() || f.isId() || f.isVersion() || f.column().equals(entity.softDeleteColumn())) {
        continue;
      }
      (audit.contains(f.column()) ? record : own).add(f);
    }
    if (!own.isEmpty()) {
      layout.section("general", 2, own.toArray(Field<?, ?>[]::new));
    }
    if (!record.isEmpty()) {
      layout.section("record", 2, record.toArray(Field<?, ?>[]::new));
    }
    for (Relation<?, ?> rel : entity.relations()) {
      if (rel.kind() != Relation.Kind.ONE_TO_MANY) {
        continue;
      }
      EntityDefinition<?> child = rel.target();
      List<Field<?, ?>> columns = new ArrayList<>(child.listColumns());
      if (columns.isEmpty()) {
        for (Field<?, ?> f : child.fields()) {
          if (!f.isInternal() && !f.isId() && !f.isVersion() && (f.isFilterable() || f.isSortable())) {
            columns.add(f);
          }
        }
      }
      if (!columns.isEmpty()) {
        layout.collection(rel, c -> c.columns(columns.toArray(Field<?, ?>[]::new)));
      }
    }
    return layout.tab(RecordLayout.HISTORY);
  }

  /**
   * The actions the caller holds the permission for: what a button and its
   * dialog need. Whether a record action applies to one record is in that
   * record's _meta.
   */
  private List<Map<String, Object>> actions(RequestContext rc, Resource r) {
    Language language = rc.language();
    List<Map<String, Object>> out = new ArrayList<>();
    for (ResourceAction a : r.actions()) {
      if (!permissions.has(rc, a.permission())) {
        continue;
      }
      Map<String, Object> action = new LinkedHashMap<>();
      action.put("name", a.name());
      action.put("label", messages.label(language, a.name(), LabelKeys.action(r.key(), a.name())));
      action.put("scope", a.scope().name().toLowerCase(Locale.ROOT));
      action.put("method", a.method());
      action.put("path", r.path() + (a.scope() == ResourceAction.Scope.RECORD ? "/{id}" : "") + a.suffix());
      action.put("confirm", a.needsConfirmation());
      List<Map<String, Object>> params = new ArrayList<>();
      for (ActionParam p : a.params()) {
        params.add(param(rc, r, a, p));
      }
      action.put("params", params);
      out.add(action);
    }
    return out;
  }

  /** Like a field (§23.6): name, label, type (ref, values), required, bounds. */
  private Map<String, Object> param(RequestContext rc, Resource r, ResourceAction a, ActionParam p) {
    Language language = rc.language();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", p.name());
    out.put("label", messages.label(language, p.name(), LabelKeys.actionParam(r.key(), a.name(), p.name())));
    Optional<Resource> ref = p.referencedEntity().flatMap(catalog::forEntity)
        .filter(target -> can(rc, target, Action.READ));
    out.put("type", ref.isPresent() ? "reference" : p.type().name());
    ref.ifPresent(target -> out.put("ref", target.key()));
    Object[] constants = p.type().javaType().getEnumConstants();
    if (constants != null) {
      List<Map<String, Object>> values = new ArrayList<>();
      for (Object c : constants) {
        Enum<?> value = (Enum<?>) c;
        values.add(pair("value", value.name(), "label", messages.label(language, value.name(),
            LabelKeys.enumValue(value))));
      }
      out.put("values", values);
    }
    out.put("required", p.isRequired());
    putIfSet(out, "maxLength", p.maxLength());
    putIfSet(out, "min", p.min());
    putIfSet(out, "max", p.max());
    if (p.type() == FieldType.DECIMAL) {
      out.put("scale", DEFAULT_SCALE);
    }
    return out;
  }

  /**
   * @param labelKey the resource key, or the child entity's name for a
   *                 collection's columns: where its label keys start
   */
  private Map<String, Object> field(RequestContext rc, String labelKey, EntityDefinition<?> entity, Field<?, ?> f,
      FieldAccess access, boolean create, boolean update) {
    Language language = rc.language();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", f.name());
    out.put("label", messages.label(language, f.name(), LabelKeys.field(labelKey, f.name())));
    Optional<Resource> ref = catalog.referenceOf(entity, f).filter(target -> can(rc, target, Action.READ));
    out.put("type", ref.isPresent() ? "reference" : f.type().name());
    ref.ifPresent(target -> out.put("ref", target.key()));
    Object[] constants = f.type().javaType().getEnumConstants();
    if (constants != null) {
      List<Map<String, Object>> values = new ArrayList<>();
      for (Object c : constants) {
        Enum<?> value = (Enum<?>) c;
        values.add(pair("value", value.name(), "label", messages.label(language, value.name(),
            LabelKeys.enumValue(value))));
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

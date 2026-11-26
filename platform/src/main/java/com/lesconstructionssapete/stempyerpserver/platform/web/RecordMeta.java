package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.LabelKeys;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.web.ResourceCatalog.Resource;

/**
 * What the caller may do with one record now (docs/ARCHITECTURE.md §23.9),
 * attached to single-record answers as "_meta":
 *
 * <pre>
 * "_meta": { "can": { "update": true, "delete": false }, "locked": ["accountType"],
 *            "actions": [{ "name": "reverse", "confirm": "Contrepasser l'écriture JE00000012 ?" }] }
 * </pre>
 *
 * can = the permission AND the resource's RecordRules; locked = the fields the
 * record's state freezes ("*": all); actions = the record actions the caller
 * holds the permission for and that apply to this row, with their
 * confirmation text filled from the row (null when the action asks none, or
 * the text can't be filled).
 */
final class RecordMeta {

  private final PermissionChecker permissions;
  private final Messages messages;

  RecordMeta(PermissionChecker permissions, Messages messages) {
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.messages = Objects.requireNonNull(messages, "messages");
  }

  Map<String, Object> describe(UnitOfWork uow, Resource r, Map<String, Object> row, RequestContext rc) {
    RecordRules rules = r.rules();
    Map<String, Object> can = new LinkedHashMap<>();
    can.put("update", holds(rc, r, Action.UPDATE) && (rules == null || rules.canUpdate(uow, rc, row)));
    can.put("delete", holds(rc, r, Action.DELETE) && (rules == null || rules.canDelete(uow, rc, row)));
    List<Map<String, Object>> actions = new ArrayList<>();
    for (ResourceAction a : r.actions()) {
      if (a.scope() == ResourceAction.Scope.RECORD && permissions.has(rc, a.permission()) && a.appliesTo(row)) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", a.name());
        action.put("confirm", a.needsConfirmation()
            ? messages.text(rc.language(), LabelKeys.actionConfirm(r.key(), a.name()), row).orElse(null)
            : null);
        actions.add(action);
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("can", can);
    out.put("locked", rules == null ? List.of() : new ArrayList<>(new TreeSet<>(rules.locked(uow, rc, row))));
    out.put("actions", actions);
    return out;
  }

  private boolean holds(RequestContext rc, Resource r, Action action) {
    return r.permission(action).map(p -> permissions.has(rc, p)).orElse(false);
  }
}

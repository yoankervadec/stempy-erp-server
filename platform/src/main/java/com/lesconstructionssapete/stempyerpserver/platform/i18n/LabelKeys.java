package com.lesconstructionssapete.stempyerpserver.platform.i18n;

/**
 * The bundle keys of the UI labels (docs/ARCHITECTURE.md §23.5), in one place
 * so the server and the completeness tests agree. Where two keys are given,
 * the first is the resource's own and the second the shared one in the
 * platform bundle (field.createdAt...), tried in that order.
 */
public final class LabelKeys {

  private LabelKeys() {
  }

  /** A menu group: the module name ("gl", "masterdata.items"). */
  public static String module(String moduleName) {
    return "module." + moduleName;
  }

  /** A resource, plural ("Products"). */
  public static String resource(String resourceKey) {
    return resourceKey;
  }

  /** A resource, singular ("Product"). */
  public static String resourceOne(String resourceKey) {
    return resourceKey + ".one";
  }

  public static String[] field(String resourceKey, String fieldName) {
    return new String[] { resourceKey + ".field." + fieldName, "field." + fieldName };
  }

  /** "enum.AccountType.ASSET": by the enum's simple name, wherever it is used. */
  public static String enumValue(Enum<?> value) {
    return "enum." + value.getDeclaringClass().getSimpleName() + "." + value.name();
  }

  /** A resource action's button ("Reverse"). */
  public static String action(String resourceKey, String action) {
    return resourceKey + ".action." + action;
  }

  /** Its confirmation question; {placeholders} are the record's fields. */
  public static String actionConfirm(String resourceKey, String action) {
    return action(resourceKey, action) + ".confirm";
  }

  /** One of its parameters: its own label, else the field label of the same name. */
  public static String[] actionParam(String resourceKey, String action, String param) {
    String[] field = field(resourceKey, param);
    return new String[] { action(resourceKey, action) + ".param." + param, field[0], field[1] };
  }

  /** A layout section: the resource's own, else a shared one ("section.general"). */
  public static String[] section(String resourceKey, String section) {
    return new String[] { resourceKey + ".section." + section, "section." + section };
  }

  /** A layout collection: the resource's own, else the child entity's plural label. */
  public static String[] collection(String resourceKey, String collection, String childKey) {
    return new String[] { resourceKey + ".collection." + collection, resource(childKey) };
  }

  /** A built-in record tab ("tab.history"). */
  public static String tab(String tab) {
    return "tab." + tab;
  }

  /** A report's title ("report.glTrialBalance"). */
  public static String report(String reportKey) {
    return "report." + reportKey;
  }

  /** One of its parameters: its own label, else a shared field label of the same name. */
  public static String[] reportParam(String reportKey, String param) {
    return new String[] { report(reportKey) + ".param." + param, "field." + param };
  }

  /** One of its columns, by path ("report.glTrialBalance.column.amounts.closing"). */
  public static String reportColumn(String reportKey, String column) {
    return report(reportKey) + ".column." + column;
  }

  /** One of its figures ("report.glAccountLedger.figure.opening"). */
  public static String reportFigure(String reportKey, String figure) {
    return report(reportKey) + ".figure." + figure;
  }

  /** An audit action ("CREATED", "POSTED"). */
  public static String auditAction(String action) {
    return "audit.action." + action;
  }
}

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

  /** An audit action ("CREATED", "POSTED"). */
  public static String auditAction(String action) {
    return "audit.action." + action;
  }
}

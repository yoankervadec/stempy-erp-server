package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.util.function.Predicate;

/**
 * What the current user may do with each field. Built once per request from the
 * user's permissions (via PermissionChecker) and passed to the query
 * translator, the patch builder and the serializer.
 */
public interface FieldAccess {

  boolean canRead(Field<?, ?> field);

  boolean canWrite(Field<?, ?> field);

  FieldAccess ALLOW_ALL = new FieldAccess() {
    @Override
    public boolean canRead(Field<?, ?> field) {
      return true;
    }

    @Override
    public boolean canWrite(Field<?, ?> field) {
      return true;
    }
  };

  /**
   * A field with no declared permission is open; otherwise the user must hold
   * that permission.
   */
  static FieldAccess forPermissions(Predicate<String> userHas) {
    return new FieldAccess() {
      @Override
      public boolean canRead(Field<?, ?> f) {
        return f.readPermission() == null || userHas.test(f.readPermission());
      }

      @Override
      public boolean canWrite(Field<?, ?> f) {
        return f.writePermission() == null || userHas.test(f.writePermission());
      }
    };
  }

}

package com.lesconstructionssapete.stempyerpserver.modules.users;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * Install-wide (one account, member of several companies), so a global table:
 * the createdByUser relation of a company's rows must find a user whatever
 * their home company is.
 *
 * Uses StandardEntity.defineGlobal rather than Entities.define: it is the
 * target of the createdByUser / updatedByUser relations, not a holder of them.
 */
public class User extends StandardEntity {

  private boolean isEnabled;
  private String userNo;
  private String name;

  public static final EntityDefinition<User> DEF = StandardEntity.defineGlobal(
      "user", "users", User::new);

  public static final Field<User, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", User::isEnabled, User::setEnabled);

  public static final Field<User, String> USER_NO = DEF.string(
      "userNo", "user_no", User::userNo, User::setUserNo)
      .filterable().sortable();

  public static final Field<User, String> NAME = DEF.string(
      "name", "name", User::name, User::setName)
      .filterable().sortable();

  public boolean isEnabled() {
    return isEnabled;
  }

  public void setEnabled(boolean isEnabled) {
    this.isEnabled = isEnabled;
  }

  public String userNo() {
    return userNo;
  }

  public void setUserNo(String userNo) {
    this.userNo = userNo;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}

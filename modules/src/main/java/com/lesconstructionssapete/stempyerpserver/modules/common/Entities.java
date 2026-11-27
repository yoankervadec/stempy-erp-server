package com.lesconstructionssapete.stempyerpserver.modules.common;

import java.util.function.Supplier;

import com.lesconstructionssapete.stempyerpserver.modules.users.User;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * How business entities declare their DEF. Adds to StandardEntity.define the
 * relations every business table has to the users module, so clients can
 * filter and sort on e.g. "createdByUser.name".
 *
 * The users module must come before any module using this (AllModules).
 */
public final class Entities {

  private Entities() {
  }

  /** Standard columns plus createdByUser / updatedByUser relations to User. */
  public static <E extends StandardEntity> EntityDefinition<E> define(String name, String table, Supplier<E> factory) {
    return withUsers(StandardEntity.define(name, table, factory));
  }

  /**
   * The same for an install-wide table shared by every company (currencies,
   * units of measure): standard columns minus company_id.
   */
  public static <E extends StandardEntity> EntityDefinition<E> defineGlobal(String name, String table,
      Supplier<E> factory) {
    return withUsers(StandardEntity.defineGlobal(name, table, factory));
  }

  private static <E extends StandardEntity> EntityDefinition<E> withUsers(EntityDefinition<E> def) {
    def.manyToOne("createdByUser", () -> User.DEF, "created_by");
    def.manyToOne("updatedByUser", () -> User.DEF, "updated_by");
    return def;
  }
}

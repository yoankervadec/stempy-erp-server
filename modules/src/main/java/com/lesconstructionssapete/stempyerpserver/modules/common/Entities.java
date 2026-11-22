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
 * The users module must be listed before any module using this (Main).
 */
public final class Entities {

  private Entities() {
  }

  /** Standard columns plus createdByUser / updatedByUser relations to User. */
  public static <E extends StandardEntity> EntityDefinition<E> define(String name, String table, Supplier<E> factory) {
    EntityDefinition<E> def = StandardEntity.define(name, table, factory);
    def.manyToOne("createdByUser", () -> User.DEF, "created_by");
    def.manyToOne("updatedByUser", () -> User.DEF, "updated_by");
    return def;
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.util.function.Supplier;

/**
 * A declared link between two entities. The target is a Supplier so two
 * entities can reference each other without a class-initialization cycle.
 *
 * MANY_TO_ONE: foreignKey is a column on the SOURCE table pointing at the
 * target's id.
 * ONE_TO_MANY: foreignKey is a column on the TARGET table pointing at the
 * source's id.
 */
public class Relation<E, T> {

  public enum Kind {
    MANY_TO_ONE, ONE_TO_MANY
  }

  private final String name;
  private final Kind kind;
  private final Supplier<EntityDefinition<T>> target;
  private final String foreignKey;

  Relation(String name, Kind kind, Supplier<EntityDefinition<T>> target, String foreignKey) {
    this.name = name;
    this.kind = kind;
    this.target = target;
    this.foreignKey = foreignKey;
  }

  public String name() {
    return name;
  }

  public Kind kind() {
    return kind;
  }

  public EntityDefinition<T> target() {
    return target.get();
  }

  public String foreignKey() {
    return foreignKey;
  }

}

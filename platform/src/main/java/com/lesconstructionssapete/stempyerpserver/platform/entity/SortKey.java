package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.util.Objects;

/** One key of an entity's default list order (EntityDefinition.defaultSort). */
public record SortKey(Field<?, ?> field, boolean descending) {

  public SortKey {
    Objects.requireNonNull(field, "field");
  }

  public static SortKey asc(Field<?, ?> field) {
    return new SortKey(field, false);
  }

  public static SortKey desc(Field<?, ?> field) {
    return new SortKey(field, true);
  }
}

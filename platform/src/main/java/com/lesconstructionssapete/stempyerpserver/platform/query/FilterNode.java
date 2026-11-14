package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.platform.entity.Operator;

/** Parsed (but not yet validated against any entity) filter tree. */
public sealed interface FilterNode {

  record And(List<FilterNode> children) implements FilterNode {
  }

  record Or(List<FilterNode> children) implements FilterNode {
  }

  record Not(FilterNode child) implements FilterNode {
  }

  /**
   * path is an API field path such as "sku" or "category.name"; value is a raw
   * JSON scalar or list.
   */
  record Condition(String path, Operator op, Object value) implements FilterNode {
  }
}

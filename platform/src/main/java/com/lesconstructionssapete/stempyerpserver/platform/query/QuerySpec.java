package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.List;

/**
 * A client query: filter, sort, offset paging, and an optional quick search
 * (the entity's title and subtitle, see QueryTranslator.searchFields).
 * `where` and `search` may be null.
 */
public record QuerySpec(FilterNode where, List<Sort> sort, int limit, int offset, String search) {

  public QuerySpec(FilterNode where, List<Sort> sort, int limit, int offset) {
    this(where, sort, limit, offset, null);
  }

  public record Sort(String path, boolean ascending) {
  }
}

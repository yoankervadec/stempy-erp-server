package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.List;

/** A client query: filter, sort, offset paging. `where` may be null. */
public record QuerySpec(FilterNode where, List<Sort> sort, int limit, int offset) {

  public record Sort(String path, boolean ascending) {
  }
}

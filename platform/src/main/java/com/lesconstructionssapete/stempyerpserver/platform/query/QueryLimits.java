package com.lesconstructionssapete.stempyerpserver.platform.query;

/** Hard caps that keep a client from building an expensive query. */
public record QueryLimits(
    int maxDepth, // nesting of and/or/not
    int maxConditions, // total leaf conditions
    int maxSorts,
    int maxPageSize,
    int defaultPageSize,
    int maxInList, // values in one "in"
    int maxRelationHops) { // "a.b.field" has 2 hops

  public static QueryLimits defaults() {
    return new QueryLimits(
        4,
        20,
        3,
        200,
        50,
        100,
        2);
  }
}

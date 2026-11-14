package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.entity.Operator;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Turns the JSON body (already read into plain Maps/Lists, e.g. by Jackson)
 * into a QuerySpec.
 * Checks shape and limits only; field names and types are checked later by the
 * QueryTranslator.
 *
 * Body shape:
 *
 * <pre>
 * <code class="json">
 * {
 *  "where": {
 *    "and": [
 *      { "field": "sku", "op": "startsWith", "value": "AB" },
 *      ...
 *    ]
 *  },
 *  "sort": [
 *    { "field": "sku", "dir": "asc" }
 *  ],
 *  "page": { "limit": 50, "offset": 0 }
 * }
 * </code>
 * </pre>
 */

public class QuerySpecParser {

  private final QueryLimits limits;

  public QuerySpecParser(QueryLimits limits) {
    this.limits = limits;
  }

  public QuerySpec parse(Map<String, ?> body) {
    State st = new State();

    FilterNode where = null;
    Object rawWhere = body.get("where");
    if (rawWhere != null) {
      where = node(rawWhere, "where", 1, st);
    }
    List<QuerySpec.Sort> sort = sorts(body.get("sort"), st);
    int[] page = page(body.get("page"), st);

    if (!st.errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", st.errors);
    }
    return new QuerySpec(where, sort, page[0], page[1]);
  }

  private static final class State {
    final List<FieldError> errors = new ArrayList<>();
    int conditions;

    void err(String at, String code, String message) {
      errors.add(new FieldError(at, code, message));
    }
  }

  private FilterNode node(Object raw, String at, int depth, State st) {
    if (!(raw instanceof Map<?, ?> m)) {
      st.err(at, "INVALID_FILTER", "must be an object");
      return null;
    }
    if (depth > limits.maxDepth()) {
      st.err(at, "TOO_DEEP", "filters may be nested at most " + limits.maxDepth() + " levels");
      return null;
    }
    int kinds = 0;
    for (String key : new String[] { "and", "or", "not", "field" }) {
      if (m.containsKey(key)) {
        kinds++;
      }
    }
    if (kinds != 1) {
      st.err(at, "INVALID_FILTER", "must contain exactly one of: and, or, not, field");
      return null;
    }
    if (m.containsKey("and")) {
      List<FilterNode> kids = group(m.get("and"), at + ".and", depth, st);
      return kids == null ? null : new FilterNode.And(kids);
    }
    if (m.containsKey("or")) {
      List<FilterNode> kids = group(m.get("or"), at + ".or", depth, st);
      return kids == null ? null : new FilterNode.Or(kids);
    }
    if (m.containsKey("not")) {
      FilterNode child = node(m.get("not"), at + ".not", depth + 1, st);
      return child == null ? null : new FilterNode.Not(child);
    }
    return condition(m, at, st);
  }

  private List<FilterNode> group(Object raw, String at, int depth, State st) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      st.err(at, "INVALID_FILTER", "must be a non-empty array");
      return null;
    }
    List<FilterNode> kids = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      FilterNode child = node(list.get(i), at + "[" + i + "]", depth + 1, st);
      if (child != null) {
        kids.add(child);
      }
    }
    return kids;
  }

  private FilterNode condition(Map<?, ?> m, String at, State st) {
    if (++st.conditions > limits.maxConditions()) {
      if (st.conditions == limits.maxConditions() + 1) {
        st.err(at, "TOO_MANY_CONDITIONS", "at most " + limits.maxConditions() + " conditions are allowed");
      }
      return null;
    }
    if (!(m.get("field") instanceof String path) || path.isBlank()) {
      st.err(at + ".field", "INVALID_FILTER", "must be a non-empty string");
      return null;
    }
    Operator op = m.get("op") instanceof String s ? Operator.fromWire(s).orElse(null) : null;
    if (op == null) {
      st.err(at + ".op", "UNKNOWN_OPERATOR", "must be one of the supported operators");
      return null;
    }
    Object value = m.get("value");
    switch (op) {
      case IN -> {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > limits.maxInList()) {
          st.err(at + ".value", "INVALID_VALUE",
              "must be an array of 1 to " + limits.maxInList() + " values");
          return null;
        }
        for (Object item : list) {
          if (!isScalar(item)) {
            st.err(at + ".value", "INVALID_VALUE", "array items must be strings, numbers or booleans");
            return null;
          }
        }
      }
      case IS_NULL -> {
        if (!(value instanceof Boolean)) {
          st.err(at + ".value", "INVALID_VALUE", "must be true or false");
          return null;
        }
      }
      default -> {
        if (!isScalar(value)) {
          st.err(at + ".value", "INVALID_VALUE", "must be a string, number or boolean");
          return null;
        }
      }
    }
    return new FilterNode.Condition(path, op, value);
  }

  private static boolean isScalar(Object v) {
    return v instanceof String || v instanceof Number || v instanceof Boolean;
  }

  private List<QuerySpec.Sort> sorts(Object raw, State st) {
    List<QuerySpec.Sort> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    if (!(raw instanceof List<?> list) || list.size() > limits.maxSorts()) {
      st.err("sort", "INVALID_SORT", "must be an array of at most " + limits.maxSorts() + " items");
      return out;
    }
    for (int i = 0; i < list.size(); i++) {
      String at = "sort[" + i + "]";
      if (!(list.get(i) instanceof Map<?, ?> m) || !(m.get("field") instanceof String path) || path.isBlank()) {
        st.err(at, "INVALID_SORT", "must be an object with a 'field'");
        continue;
      }
      Object dir = m.get("dir");
      if (dir != null && !"asc".equals(dir) && !"desc".equals(dir)) {
        st.err(at + ".dir", "INVALID_SORT", "must be 'asc' or 'desc'");
        continue;
      }
      out.add(new QuerySpec.Sort(path, !"desc".equals(dir)));
    }
    return out;
  }

  private int[] page(Object raw, State st) {
    int limit = limits.defaultPageSize();
    int offset = 0;
    if (raw != null) {
      if (!(raw instanceof Map<?, ?> m)) {
        st.err("page", "INVALID_PAGE", "must be an object");
      } else {
        if (m.containsKey("limit")) {
          if (m.get("limit") instanceof Integer n && n >= 1 && n <= limits.maxPageSize()) {
            limit = n;
          } else {
            st.err("page.limit", "INVALID_PAGE", "must be an integer from 1 to " + limits.maxPageSize());
          }
        }
        if (m.containsKey("offset")) {
          if (m.get("offset") instanceof Integer n && n >= 0) {
            offset = n;
          } else {
            st.err("page.offset", "INVALID_PAGE", "must be an integer >= 0");
          }
        }
      }
    }
    return new int[] { limit, offset };
  }
}

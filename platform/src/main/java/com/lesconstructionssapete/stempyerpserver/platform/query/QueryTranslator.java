package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldAccess;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Operator;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Translates a QuerySpec into parameterized SQL for one entity.
 *
 * Safety model:
 * - client text never reaches the SQL string: only identifiers from EntityDef
 * and "?" placeholders
 * - every value is coerced to the field's type and bound
 * - the tenant (company) and soft-delete conditions are added here, first, and
 * cannot be omitted
 * - a field the user cannot read is "unknown" for filtering and sorting (no
 * side-channel leaks)
 * - to-one relations become LEFT JOINs; to-many relations become EXISTS
 * subqueries (no row duplication)
 *
 */
public final class QueryTranslator {

  private static final String FALSE = "1 = 0";

  private final QueryLimits limits;

  public QueryTranslator(QueryLimits limits) {
    this.limits = limits;
  }

  /** One field the quick search looks in, and how. */
  public record SearchField(Field<?, ?> field, Operator op) {
  }

  /**
   * Where QuerySpec.search looks: the entity's title field (starts with) and
   * subtitle field (contains), each only if it is a string the caller can read
   * and filter on. Empty: the entity can't be searched this way.
   */
  public static List<SearchField> searchFields(EntityDefinition<?> entity, FieldAccess access) {
    List<SearchField> out = new ArrayList<>();
    Field<?, ?> title = entity.titleField();
    if (searchable(title, access)) {
      out.add(new SearchField(title, Operator.STARTS_WITH));
    }
    entity.subtitleField().filter(f -> f != title && searchable(f, access))
        .ifPresent(f -> out.add(new SearchField(f, Operator.CONTAINS)));
    return out;
  }

  private static boolean searchable(Field<?, ?> f, FieldAccess access) {
    return f != null && f.type() == FieldType.STRING && f.isFilterable() && !f.isInternal() && access.canRead(f);
  }

  public <E> SQLQuery<E> translate(EntityDefinition<E> root, QuerySpec spec, long companyId, FieldAccess access) {
    return translate(root, spec, companyId, access, false);
  }

  /**
   * @param lookAhead read one row more than the page's limit, so the caller can
   *                  tell whether another page follows (Page.hasMore)
   */
  public <E> SQLQuery<E> translate(EntityDefinition<E> root, QuerySpec spec, long companyId, FieldAccess access,
      boolean lookAhead) {
    Build<E> b = new Build<>(root, access, companyId);

    // 1. Server-owned predicates come first and are never client-controlled.
    StringBuilder where = new StringBuilder();
    if (root.companyColumn() != null) {
      where.append("t0.").append(root.companyColumn()).append(" = ?");
      b.whereBinds.add(new Bind(FieldType.LONG, companyId));
    } else {
      where.append("TRUE");
    }
    if (root.softDeleteColumn() != null) {
      where.append(" AND t0.").append(root.softDeleteColumn()).append(" IS NULL");
    }

    // 2. Client filter, then the quick search (title starts with OR subtitle contains).
    if (spec.where() != null) {
      where.append(" AND ").append(b.render(spec.where()));
    }
    if (spec.search() != null) {
      List<FilterNode> any = new ArrayList<>();
      for (SearchField sf : searchFields(root, access)) {
        any.add(new FilterNode.Condition(sf.field().name(), sf.op(), spec.search()));
      }
      if (any.isEmpty()) {
        b.errors.add(new FieldError("search", "NOT_SEARCHABLE", root.name() + " has no text title to search in"));
      } else {
        where.append(" AND ").append(b.render(any.size() == 1 ? any.get(0) : new FilterNode.Or(any)));
      }
    }

    // 3. Client sort, then the id as a deterministic tiebreaker.
    List<String> order = new ArrayList<>();
    boolean sortedById = false;
    for (QuerySpec.Sort s : spec.sort()) {
      String expr = b.sortExpression(s.path());
      if (expr != null) {
        order.add(expr + (s.ascending() ? " ASC" : " DESC"));
        sortedById |= s.path().equals("id");
      }
    }
    if (!sortedById) {
      order.add("t0." + root.id().column() + " ASC");
    }

    if (!b.errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", b.errors);
    }

    // 4. Assemble. Bind order must follow the order "?" appear: joins, where,
    // limit, offset.
    List<Field<E, ?>> selected = root.selectFields(access);
    List<Field<E, ?>> visible = root.readableFields(access);

    StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < selected.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append("t0.").append(selected.get(i).column());
    }
    sql.append(" FROM ").append(root.table()).append(" t0").append(b.joins)
        .append(" WHERE ").append(where)
        .append(" ORDER BY ").append(String.join(", ", order))
        .append(" LIMIT ? OFFSET ?");

    List<Bind> binds = new ArrayList<>(b.joinBinds);
    binds.addAll(b.whereBinds);
    long limit = Math.max(1, Math.min(spec.limit(), limits.maxPageSize()));
    binds.add(new Bind(FieldType.LONG, lookAhead ? limit + 1 : limit));
    binds.add(new Bind(FieldType.LONG, (long) Math.max(0, spec.offset())));

    return new SQLQuery<>(root, sql.toString(), binds, selected, visible);
  }

  /**
   * '!' rather than a backslash as the LIKE escape character: a backslash in a
   * MySQL string literal means different things depending on the
   * NO_BACKSLASH_ESCAPES sql_mode, '!' does not.
   */
  private static final char LIKE_ESCAPE = '!';

  private static String escapeLike(String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
        out.append(LIKE_ESCAPE);
      }
      out.append(ch);
    }
    return out.toString();
  }

  private static String sqlOp(Operator op) {
    return switch (op) {
      case EQUALS -> "=";
      case GREATER_THAN -> ">";
      case GREATER_THAN_OR_EQUALS -> ">=";
      case LESS_THAN -> "<";
      case LESS_THAN_OR_EQUALS -> "<=";
      default -> throw new IllegalStateException("No simple SQL form for " + op);
    };
  }

  /** Mutable state for translating one query. */
  private final class Build<E> {
    final EntityDefinition<E> root;
    final FieldAccess access;
    final long companyId;
    final List<FieldError> errors = new ArrayList<>();
    final StringBuilder joins = new StringBuilder();
    final List<Bind> joinBinds = new ArrayList<>();
    final List<Bind> whereBinds = new ArrayList<>();
    final Map<String, String> joinAliases = new HashMap<>();
    int aliasCounter = 1;

    Build(EntityDefinition<E> root, FieldAccess access, long companyId) {
      this.root = root;
      this.access = access;
      this.companyId = companyId;
    }

    void err(String path, String code, String message) {
      errors.add(new FieldError(path, code, message));
    }

    String nextAlias() {
      return "t" + aliasCounter++;
    }

    // ---------------- filter ----------------

    String render(FilterNode node) {
      return switch (node) {
        case FilterNode.And and -> group(and.children(), " AND ");
        case FilterNode.Or or -> group(or.children(), " OR ");
        case FilterNode.Not not -> "NOT (" + render(not.child()) + ")";
        case FilterNode.Condition c -> condition(c);
      };
    }

    private String group(List<FilterNode> kids, String glue) {
      StringJoiner sj = new StringJoiner(glue, "(", ")");
      for (FilterNode kid : kids) {
        sj.add(render(kid));
      }
      return sj.toString();
    }

    private String condition(FilterNode.Condition c) {
      String[] segs = c.path().split("\\.", -1);
      if (segs.length - 1 > limits.maxRelationHops()) {
        err(c.path(), "PATH_TOO_DEEP", "at most " + limits.maxRelationHops() + " relations may be traversed");
        return FALSE;
      }
      return renderPath(root, "t0", "", segs, 0, c);
    }

    private <T> String renderPath(EntityDefinition<T> def, String alias, String prefix, String[] segs, int i,
        FilterNode.Condition c) {
      String seg = segs[i];
      if (i == segs.length - 1) {
        Field<T, ?> f = def.field(seg).orElse(null);
        if (f == null || f.isInternal() || !access.canRead(f)) {
          err(c.path(), "UNKNOWN_FIELD", "unknown field '" + seg + "'");
          return FALSE;
        }
        if (!f.isFilterable()) {
          err(c.path(), "NOT_FILTERABLE", "'" + seg + "' cannot be used in filters");
          return FALSE;
        }
        return leaf(f, alias, c);
      }
      Relation<T, ?> rel = def.relation(seg).orElse(null);
      if (rel == null) {
        err(c.path(), "UNKNOWN_FIELD", "unknown field '" + seg + "'");
        return FALSE;
      }
      return hop(def, rel, alias, prefix, segs, i, c);
    }

    private <T, U> String hop(EntityDefinition<T> def, Relation<T, U> rel, String alias, String prefix,
        String[] segs, int i, FilterNode.Condition c) {
      EntityDefinition<U> target = rel.target();
      if (rel.kind() == Relation.Kind.MANY_TO_ONE) {
        String child = toOneAlias(alias, prefix + rel.name(), rel, target);
        return renderPath(target, child, prefix + rel.name() + ".", segs, i + 1, c);
      }
      // ONE_TO_MANY becomes EXISTS; only the child's own fields may follow (no
      // further hops), so the subquery never needs joins of its own.
      if (i + 1 != segs.length - 1) {
        err(c.path(), "PATH_NOT_SUPPORTED", "only direct fields of '" + rel.name() + "' can be filtered");
        return FALSE;
      }
      String a = nextAlias();
      StringBuilder s = new StringBuilder("EXISTS (SELECT 1 FROM ").append(target.table()).append(' ').append(a)
          .append(" WHERE ").append(a).append('.').append(rel.foreignKey())
          .append(" = ").append(alias).append('.').append(def.id().column());
      if (target.companyColumn() != null) {
        s.append(" AND ").append(a).append('.').append(target.companyColumn()).append(" = ?");
        whereBinds.add(new Bind(FieldType.LONG, companyId));
      }
      if (target.softDeleteColumn() != null) {
        s.append(" AND ").append(a).append('.').append(target.softDeleteColumn()).append(" IS NULL");
      }
      String inner = renderPath(target, a, "", segs, i + 1, c);
      return s.append(" AND ").append(inner).append(')').toString();
    }

    private <T, U> String toOneAlias(String parentAlias, String key, Relation<T, U> rel, EntityDefinition<U> target) {
      String existing = joinAliases.get(key);
      if (existing != null) {
        return existing;
      }
      String a = nextAlias();
      joinAliases.put(key, a);
      joins.append(" LEFT JOIN ").append(target.table()).append(' ').append(a)
          .append(" ON ").append(a).append('.').append(target.id().column())
          .append(" = ").append(parentAlias).append('.').append(rel.foreignKey());
      if (target.companyColumn() != null) {
        joins.append(" AND ").append(a).append('.').append(target.companyColumn()).append(" = ?");
        joinBinds.add(new Bind(FieldType.LONG, companyId));
      }
      if (target.softDeleteColumn() != null) {
        joins.append(" AND ").append(a).append('.').append(target.softDeleteColumn()).append(" IS NULL");
      }
      return a;
    }

    private <T, V> String leaf(Field<T, V> f, String alias, FilterNode.Condition c) {
      String col = alias + "." + f.column();
      FieldType<V> type = f.type();
      Operator op = c.op();

      if (!type.operators().contains(op)) {
        err(c.path(), "operator_not_allowed",
            "operator '" + op.wire() + "' is not allowed on " + type.name() + " fields");
        return FALSE;
      }
      if (op == Operator.IS_NULL) {
        return col + (Boolean.TRUE.equals(c.value()) ? " IS NULL" : " IS NOT NULL");
      }
      if (op == Operator.IN) {
        StringJoiner sj = new StringJoiner(", ", col + " IN (", ")");
        List<Bind> pending = new ArrayList<>();
        for (Object raw : (List<?>) c.value()) {
          V v = coerce(f, raw, c.path());
          if (v == null) {
            return FALSE;
          }
          sj.add("?");
          pending.add(new Bind(type, v));
        }
        whereBinds.addAll(pending);
        return sj.toString();
      }

      V v = coerce(f, c.value(), c.path());
      if (v == null) {
        return FALSE;
      }
      if (op == Operator.CONTAINS || op == Operator.STARTS_WITH) {
        String text = escapeLike(v.toString());
        whereBinds.add(new Bind(FieldType.STRING, op == Operator.CONTAINS ? "%" + text + "%" : text + "%"));
        // MySQL LIKE is case-insensitive under the default *_ci collations.
        return col + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'";
      }
      whereBinds.add(new Bind(type, v));
      if (op == Operator.NOT_EQUALS) {
        // Null-safe "not equal" (MySQL has no IS DISTINCT FROM): a NULL column
        // counts as different from any value, as a user would expect.
        return "NOT (" + col + " <=> ?)";
      }
      return col + " " + sqlOp(op) + " ?";
    }

    private <T, V> V coerce(Field<T, V> f, Object raw, String path) {
      try {
        V v = f.type().coerce(raw);
        if (v != null) {
          return v;
        }
        err(path, "INVALID_VALUE", "a value is required");
      } catch (IllegalArgumentException e) {
        err(path, "INVALID_VALUE", e.getMessage());
      }
      return null;
    }

    // ---------------- sort ----------------

    String sortExpression(String path) {
      String[] segs = path.split("\\.", -1);
      if (segs.length - 1 > limits.maxRelationHops()) {
        err(path, "PATH_TOO_DEEP", "at most " + limits.maxRelationHops() + " relations may be traversed");
        return null;
      }
      return sortPath(root, "t0", "", segs, 0, path);
    }

    private <T> String sortPath(EntityDefinition<T> def, String alias, String prefix, String[] segs, int i,
        String path) {
      String seg = segs[i];
      if (i == segs.length - 1) {
        Field<T, ?> f = def.field(seg).orElse(null);
        if (f == null || f.isInternal() || !access.canRead(f)) {
          err(path, "UNKNOWN_FIELD", "unknown field '" + seg + "'");
          return null;
        }
        if (!f.isSortable()) {
          err(path, "NOT_SORTABLE", "'" + seg + "' cannot be used for sorting");
          return null;
        }
        return alias + "." + f.column();
      }
      Relation<T, ?> rel = def.relation(seg).orElse(null);
      if (rel == null) {
        err(path, "UNKNOWN_FIELD", "unknown field '" + seg + "'");
        return null;
      }
      return sortHop(rel, alias, prefix, segs, i, path);
    }

    private <T, U> String sortHop(Relation<T, U> rel, String alias, String prefix, String[] segs, int i,
        String path) {
      if (rel.kind() != Relation.Kind.MANY_TO_ONE) {
        err(path, "NOT_SORTABLE", "cannot sort through the to-many relation '" + rel.name() + "'");
        return null;
      }
      EntityDefinition<U> target = rel.target();
      String child = toOneAlias(alias, prefix + rel.name(), rel, target);
      return sortPath(target, child, prefix + rel.name() + ".", segs, i + 1, path);
    }
  }
}

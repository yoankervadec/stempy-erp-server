package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Format;

/**
 * A report described for the generic report page (docs/ARCHITECTURE.md
 * §23.11): its parameter form, the rows' columns, the totals. The module
 * serves the route (GET {path}?param=...) itself; GET /meta lists the report
 * to callers holding its permission, in the module's menu group.
 *
 * <pre>
 * Report.of("glTrialBalance", "/gl/reports/trial-balance").permission("gl.report.read")
 *     .param(ActionParam.date("from").required()).param(ActionParam.date("to").required())
 *     .rows("rows").level("level").emphasis("isHeading")
 *     .column("accountNo", FieldType.STRING)
 *     .column("amounts.closing", FieldType.DECIMAL, Format.MONEY).summedAs("closing")
 *     .totals("totals")
 * </pre>
 *
 * A column is a path into a row ("amounts.closing"); its total, if any, is
 * the path summedAs() names inside the totals object. A figure is a value of
 * the whole answer (a path into the response), shown above the rows. Labels
 * (LabelKeys): "report.{key}", each parameter "report.{key}.param.{name}"
 * (else "field.{name}"), each column "report.{key}.column.{path}", each figure
 * "report.{key}.figure.{path}"; ModuleHost refuses to start when one is
 * missing.
 */
public final class Report {

  /**
   * One column: a path into a row, its type, how to show it, and where its
   * total is in the totals object (null: no total).
   */
  public record Column(String path, FieldType<?> type, Format format, String total) {
  }

  /** A value of the whole answer, shown above the rows (an account's opening balance). */
  public record Figure(String path, FieldType<?> type, Format format) {
  }

  private final String key;
  private final String path;
  private String permission;
  private final List<ActionParam> params = new ArrayList<>();
  private final List<Column> columns = new ArrayList<>();
  private final List<Figure> figures = new ArrayList<>();
  private String rows;
  private String level;
  private String emphasis;
  private String totals;

  private Report(String key, String path) {
    if (key == null || !key.matches("[a-z][A-Za-z0-9]*")) {
      throw new IllegalArgumentException("Invalid report key: " + key);
    }
    if (path == null || !path.matches("(/[a-z][a-z0-9-]*)+")) {
      throw new IllegalArgumentException(key + ": path like /gl/reports/trial-balance expected, got " + path);
    }
    this.key = key;
    this.path = path;
  }

  public static Report of(String key, String path) {
    return new Report(key, path);
  }

  public Report permission(String permission) {
    this.permission = Objects.requireNonNull(permission, "permission");
    return this;
  }

  public Report param(ActionParam param) {
    if (params.stream().anyMatch(p -> p.name().equals(param.name()))) {
      throw new IllegalArgumentException(key + ": parameter " + param.name() + " declared twice");
    }
    params.add(param);
    return this;
  }

  /** The response's array of rows. */
  public Report rows(String rowsPath) {
    this.rows = requirePath(rowsPath);
    return this;
  }

  /** A row's indentation depth (an integer field of the row), for hierarchies such as a chart. */
  public Report level(String levelPath) {
    this.level = requirePath(levelPath);
    return this;
  }

  /** A boolean field of the row that marks rows to show in bold (headings, subtotals). */
  public Report emphasis(String emphasisPath) {
    this.emphasis = requirePath(emphasisPath);
    return this;
  }

  public Report column(String columnPath, FieldType<?> type) {
    return column(columnPath, type, null);
  }

  public Report column(String columnPath, FieldType<?> type, Format format) {
    requirePath(columnPath);
    if (columns.stream().anyMatch(c -> c.path().equals(columnPath))) {
      throw new IllegalArgumentException(key + ": column " + columnPath + " declared twice");
    }
    columns.add(new Column(columnPath, Objects.requireNonNull(type, "type"), format, null));
    return this;
  }

  /** The last column's total is at this path in the totals object. */
  public Report summedAs(String totalPath) {
    if (columns.isEmpty()) {
      throw new IllegalStateException(key + ": summedAs() follows a column");
    }
    Column last = columns.remove(columns.size() - 1);
    columns.add(new Column(last.path(), last.type(), last.format(), requirePath(totalPath)));
    return this;
  }

  /** A value of the whole answer, shown above the rows. */
  public Report figure(String figurePath, FieldType<?> type, Format format) {
    requirePath(figurePath);
    figures.add(new Figure(figurePath, Objects.requireNonNull(type, "type"), format));
    return this;
  }

  /** The response's totals object (same column paths). */
  public Report totals(String totalsPath) {
    this.totals = requirePath(totalsPath);
    return this;
  }

  /** Fails on a report missing its permission, rows or columns. */
  void check() {
    if (permission == null || rows == null || columns.isEmpty()) {
      throw new IllegalStateException("Report " + key + " needs a permission, rows and at least one column");
    }
  }

  public String key() {
    return key;
  }

  public String path() {
    return path;
  }

  public String permission() {
    return permission;
  }

  public List<ActionParam> params() {
    return List.copyOf(params);
  }

  public List<Column> columns() {
    return List.copyOf(columns);
  }

  public List<Figure> figures() {
    return List.copyOf(figures);
  }

  public String rows() {
    return rows;
  }

  /** Null when the rows are flat. */
  public String level() {
    return level;
  }

  /** Null when no row stands out. */
  public String emphasis() {
    return emphasis;
  }

  /** Null when the report has no totals. */
  public String totals() {
    return totals;
  }

  private String requirePath(String p) {
    if (p == null || !p.matches("[a-z][A-Za-z0-9]*(\\.[a-z][A-Za-z0-9]*)*")) {
      throw new IllegalArgumentException(key + ": invalid path " + p);
    }
    return p;
  }
}

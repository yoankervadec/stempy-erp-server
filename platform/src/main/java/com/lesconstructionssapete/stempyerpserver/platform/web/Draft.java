package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;

/**
 * A document as it would be saved (docs/ARCHITECTURE.md §23.8): the stored
 * header and lines with the request's changes laid over them, values typed
 * (BigDecimal, LocalDate...; a value the client sent badly is null, and is
 * reported by the checks). What DocumentRules compute and check.
 *
 * Lines are indexed in the order of the request's array (so a problem on
 * index 2 is reported on "lines[2]..."), or the stored order when the
 * request leaves the collection out.
 */
public final class Draft {

  private final Long id;
  private final Map<String, Object> header;
  private final Map<String, List<Map<String, Object>>> collections;
  private final Map<String, Object> computedHeader = new LinkedHashMap<>();
  private final Map<String, List<Map<String, Object>>> computedLines = new LinkedHashMap<>();
  private final List<FieldError> warnings = new ArrayList<>();

  Draft(Long id, Map<String, Object> header, Map<String, List<Map<String, Object>>> collections) {
    this.id = id;
    this.header = header;
    this.collections = collections;
    collections.forEach((name, lines) -> {
      List<Map<String, Object>> computed = new ArrayList<>();
      lines.forEach(l -> computed.add(new LinkedHashMap<>()));
      computedLines.put(name, computed);
    });
  }

  /** Empty for a new document. */
  public Optional<Long> id() {
    return Optional.ofNullable(id);
  }

  /** A header value, typed; null when absent. */
  public Object get(String field) {
    return header.get(field);
  }

  /** A decimal header value; null when absent. */
  public BigDecimal decimal(String field) {
    return header.get(field) instanceof BigDecimal d ? d : null;
  }

  public int size(String collection) {
    return lines(collection).size();
  }

  /** A line's value, typed; null when absent. */
  public Object get(String collection, int index, String field) {
    return lines(collection).get(index).get(field);
  }

  /** A line's decimal value; null when absent. */
  public BigDecimal decimal(String collection, int index, String field) {
    return get(collection, index, field) instanceof BigDecimal d ? d : null;
  }

  /** Sets a header value the server computes (written whatever the field's access). */
  public void set(String field, Object value) {
    header.put(field, value);
    computedHeader.put(field, value);
  }

  /** Sets a line value the server computes. */
  public void set(String collection, int index, String field, Object value) {
    lines(collection).get(index).put(field, value);
    computedLines.get(collection).get(index).put(field, value);
  }

  /** A non-blocking remark, translated like an error ("error.{code}" with params). */
  public void warn(String field, String code, String message, Map<String, Object> params) {
    warnings.add(new FieldError(field, code, message, params));
  }

  // ---- for DocumentEndpoints ----

  Map<String, Object> header() {
    return header;
  }

  List<Map<String, Object>> lines(String collection) {
    List<Map<String, Object>> lines = collections.get(collection);
    if (lines == null) {
      throw new IllegalArgumentException("No collection " + collection);
    }
    return lines;
  }

  Map<String, Object> computedHeader() {
    return computedHeader;
  }

  Map<String, Object> computedLine(String collection, int index) {
    return computedLines.get(collection).get(index);
  }

  List<FieldError> warnings() {
    return Collections.unmodifiableList(warnings);
  }
}

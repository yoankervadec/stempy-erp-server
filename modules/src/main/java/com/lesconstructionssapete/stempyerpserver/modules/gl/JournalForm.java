package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.GeneralLedgerApi.JournalEntryRequest;
import com.lesconstructionssapete.stempyerpserver.modules.gl.GeneralLedgerApi.LineRequest;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Turns request bodies into posting requests: types only, every problem at
 * once. The posting rules (accounts, balance, rounding, periods) are
 * JournalPostingService's.
 *
 * <pre>
 * {"postingDate": "2026-04-30", "description": "...",
 *  "lines": [{"accountNo": "5000", "debit": "100.00", "description": "...", "dimensions": {"STORE": "012"}},
 *            {"accountNo": "1010", "credit": "100.00"}]}
 * </pre>
 *
 * Amounts as strings ("100.00") or JSON numbers.
 */
final class JournalForm {

  private JournalForm() {
  }

  /** A manual entry (source MANUAL). */
  static JournalEntryRequest manualEntry(JsonNode body) {
    requireObject(body);
    var v = Validation.check();
    LocalDate date = null;
    String description = null;
    List<LineRequest> lines = new ArrayList<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      String f = e.getKey();
      JsonNode n = e.getValue();
      switch (f) {
        case "postingDate" -> date = date(v, f, n);
        case "description" -> description = text(v, f, n);
        case "lines" -> {
          if (!n.isArray()) {
            v.add(f, "INVALID_TYPE", "must be an array of lines");
          } else {
            for (int i = 0; i < n.size(); i++) {
              lines.add(line(v, "lines[" + i + "]", n.get(i)));
            }
          }
        }
        default -> v.add(f, "UNKNOWN_FIELD", "unknown field '" + f + "'");
      }
    }
    v.throwIfInvalid();
    return new JournalEntryRequest(date, description, GeneralLedgerApi.MANUAL, null, null, lines);
  }

  /** A reversal: {"postingDate": "...", "description": "..." (optional)}. */
  record Reversal(LocalDate postingDate, String description) {
  }

  static Reversal reversal(JsonNode body) {
    requireObject(body);
    var v = Validation.check();
    LocalDate date = null;
    String description = null;
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      switch (e.getKey()) {
        case "postingDate" -> date = date(v, e.getKey(), e.getValue());
        case "description" -> description = e.getValue().isNull() ? null : text(v, e.getKey(), e.getValue());
        default -> v.add(e.getKey(), "UNKNOWN_FIELD", "unknown field '" + e.getKey() + "'");
      }
    }
    v.required("postingDate", date).maxLength("description", description, 255);
    v.throwIfInvalid();
    return new Reversal(date, description);
  }

  private static LineRequest line(Validation v, String at, JsonNode n) {
    if (!n.isObject()) {
      v.add(at, "INVALID_TYPE", "must be an object");
      return new LineRequest(null, null, null, null, Map.of());
    }
    String accountNo = null;
    BigDecimal debit = null;
    BigDecimal credit = null;
    String description = null;
    Map<String, String> dimensions = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
      var e = it.next();
      String f = at + "." + e.getKey();
      JsonNode value = e.getValue();
      switch (e.getKey()) {
        case "accountNo" -> accountNo = text(v, f, value);
        case "debit" -> debit = amount(v, f, value);
        case "credit" -> credit = amount(v, f, value);
        case "description" -> description = value.isNull() ? null : text(v, f, value);
        case "dimensions" -> {
          if (value.isObject()) {
            value.fields().forEachRemaining(d -> {
              if (d.getValue().isTextual() || d.getValue().isIntegralNumber()) {
                dimensions.put(d.getKey(), d.getValue().asText());
              } else {
                v.add(f + "." + d.getKey(), "INVALID_TYPE", "must be a value code (a string)");
              }
            });
          } else if (!value.isNull()) {
            v.add(f, "INVALID_TYPE", "must be an object of {dimension code: value code}");
          }
        }
        default -> v.add(f, "UNKNOWN_FIELD", "unknown field '" + e.getKey() + "'");
      }
    }
    return new LineRequest(accountNo, debit, credit, description, dimensions);
  }

  private static void requireObject(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
  }

  private static String text(Validation v, String field, JsonNode n) {
    if (n.isNull()) {
      return null;
    }
    if (!n.isTextual()) {
      v.add(field, "INVALID_TYPE", "must be a string");
      return null;
    }
    return n.textValue();
  }

  private static LocalDate date(Validation v, String field, JsonNode n) {
    if (n.isNull()) {
      return null;
    }
    try {
      if (n.isTextual()) {
        return LocalDate.parse(n.textValue());
      }
    } catch (DateTimeParseException e) {
      // reported below
    }
    v.add(field, "INVALID_FORMAT", "must be a date such as 2026-04-30");
    return null;
  }

  private static BigDecimal amount(Validation v, String field, JsonNode n) {
    if (n.isNull()) {
      return null;
    }
    try {
      if (n.isNumber()) {
        return n.decimalValue();
      }
      if (n.isTextual()) {
        return new BigDecimal(n.textValue().strip());
      }
    } catch (NumberFormatException e) {
      // reported below
    }
    v.add(field, "INVALID_TYPE", "must be an amount such as \"100.00\"");
    return null;
  }
}

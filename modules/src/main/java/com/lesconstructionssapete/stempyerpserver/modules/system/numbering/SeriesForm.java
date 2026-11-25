package com.lesconstructionssapete.stempyerpserver.modules.system.numbering;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;

/**
 * Turns request bodies into values: every field is type-checked and every
 * problem is reported at once. Rules that need the document type or the
 * stored series (pattern, gapless, in use) are the orchestrator's.
 *
 * PATCH semantics: a field left out keeps its value; only description may be
 * sent as null (cleared).
 */
final class SeriesForm {

  /** Highest start value / next number: 18 digits, far from BIGINT limits. */
  static final long MAX_NUMBER = 999_999_999_999_999_999L;

  /** Sent back by the API, never accepted. */
  private static final Set<String> READ_ONLY = Set.of("id", "used", "createdAt", "createdBy", "updatedAt",
      "updatedBy");

  /**
   * A parsed POST or PATCH body. {@code documentType} is set on create,
   * {@code version} on change; {@code defaultGiven} says whether isDefault was
   * sent.
   */
  record Result(String documentType, SeriesFields fields, Integer version, boolean defaultGiven) {
  }

  /** A parsed set-next body. */
  record SetNext(Map<String, String> params, long nextNumber) {
  }

  private SeriesForm() {
  }

  /**
   * @param base     the stored series (PATCH) or null (POST)
   * @throws ValidationFailedException listing every problem
   */
  static Result apply(JsonNode body, SeriesFields base) {
    requireObject(body);
    boolean creating = base == null;
    var v = Validation.check();
    String documentType = null;
    String code = creating ? null : base.code();
    String description = creating ? null : base.description();
    String pattern = creating ? null : base.pattern();
    Mode mode = creating ? null : base.mode();
    long startValue = creating ? 1 : base.startValue();
    boolean isDefault = !creating && base.isDefault();
    boolean isEnabled = creating || base.isEnabled();
    Integer version = null;

    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      String f = e.getKey();
      JsonNode n = e.getValue();
      switch (f) {
        case "documentType" -> {
          if (creating) {
            documentType = text(v, f, n, false, null);
          } else {
            v.add(f, "READ_ONLY", "cannot be changed; create a new series instead");
          }
        }
        case "code" -> {
          if (creating) {
            code = text(v, f, n, false, null);
          } else {
            v.add(f, "READ_ONLY", "cannot be changed: callers ask for a series by its code");
          }
        }
        case "description" -> description = text(v, f, n, true, description);
        case "pattern" -> pattern = text(v, f, n, false, pattern);
        case "mode" -> mode = enumValue(v, f, n, Mode.class, mode);
        case "startValue" -> startValue = number(v, f, n, startValue);
        case "isDefault" -> isDefault = bool(v, f, n, isDefault);
        case "isEnabled" -> isEnabled = bool(v, f, n, isEnabled);
        case "version" -> {
          if (creating) {
            v.add(f, "READ_ONLY", "is set by the server");
          } else if (n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= 0) {
            version = n.intValue();
          } else {
            v.add(f, "INVALID_TYPE", "must be a whole number");
          }
        }
        default -> v.add(f, READ_ONLY.contains(f) ? "READ_ONLY" : "UNKNOWN_FIELD",
            READ_ONLY.contains(f) ? "is set by the server" : "unknown field '" + f + "'");
      }
    }
    if (creating) {
      v.required("documentType", documentType).required("code", code).required("pattern", pattern)
          .required("mode", mode);
      if (code != null && !DocumentType.SERIES_CODE.matcher(code).matches()) {
        v.add("code", "INVALID_FORMAT", "upper-case letters, digits, '-' and '_', at most 50 characters");
      }
    } else if (!body.has("version")) {
      v.add("version", "REQUIRED", "send the version you last read");
    }
    v.maxLength("description", description, 255);
    v.throwIfInvalid();
    return new Result(documentType, new SeriesFields(code, description, pattern, mode, startValue, isDefault,
        isEnabled), version, body.has("isDefault"));
  }

  /** {@code {"params": {"STORE_NO": "12"}, "nextNumber": 5001}}; params may be left out. */
  static SetNext setNext(JsonNode body) {
    requireObject(body);
    var v = Validation.check();
    Map<String, String> params = new LinkedHashMap<>();
    Long next = null;
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      String f = e.getKey();
      JsonNode n = e.getValue();
      switch (f) {
        case "params" -> {
          if (n.isObject()) {
            n.fields().forEachRemaining(p -> {
              if (p.getValue().isTextual() || p.getValue().isIntegralNumber()) {
                params.put(p.getKey(), p.getValue().asText());
              } else {
                v.add("params." + p.getKey(), "INVALID_TYPE", "must be a string");
              }
            });
          } else if (!n.isNull()) {
            v.add(f, "INVALID_TYPE", "must be a JSON object");
          }
        }
        case "nextNumber" -> next = number(v, f, n, null);
        default -> v.add(f, "UNKNOWN_FIELD", "unknown field '" + f + "'");
      }
    }
    v.required("nextNumber", next);
    v.throwIfInvalid();
    return new SetNext(params, next);
  }

  private static void requireObject(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
  }

  // ---- typed readers: on a wrong type they report it and keep the old value ----

  private static String text(Validation v, String field, JsonNode n, boolean nullable, String current) {
    if (n.isNull() || (n.isTextual() && n.textValue().isBlank())) {
      if (!nullable) {
        v.add(field, "REQUIRED", "is required");
        return current;
      }
      return null;
    }
    if (!n.isTextual()) {
      v.add(field, "INVALID_TYPE", "must be a string");
      return current;
    }
    return n.textValue().strip();
  }

  private static Long number(Validation v, String field, JsonNode n, Long current) {
    if (!n.isIntegralNumber() || !n.canConvertToLong()) {
      v.add(field, "INVALID_TYPE", "must be a whole number");
      return current;
    }
    long value = n.longValue();
    if (value < 1 || value > MAX_NUMBER) {
      v.add(field, "OUT_OF_RANGE", "must be between 1 and " + MAX_NUMBER);
      return current;
    }
    return value;
  }

  private static boolean bool(Validation v, String field, JsonNode n, boolean current) {
    if (n.isBoolean()) {
      return n.booleanValue();
    }
    v.add(field, "INVALID_TYPE", "must be true or false");
    return current;
  }

  private static <E extends Enum<E>> E enumValue(Validation v, String field, JsonNode n, Class<E> type, E current) {
    if (n.isTextual()) {
      try {
        return Enum.valueOf(type, n.textValue().strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        // reported below
      }
    }
    v.add(field, "INVALID_VALUE", "must be one of " + Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    return current;
  }
}

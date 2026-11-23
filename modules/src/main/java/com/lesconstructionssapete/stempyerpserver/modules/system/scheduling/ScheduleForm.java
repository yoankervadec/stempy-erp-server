package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.MisfirePolicy;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleSpec;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule.ScheduleType;

/**
 * Turns a create (POST) or change (PATCH) body into ScheduleFields: every
 * field is type-checked, the result is checked as a whole (cron, interval,
 * time zone, window, limits), and every problem is reported at once.
 *
 * PATCH semantics: a field left out keeps its value, a field sent as null
 * clears it (where it may be empty).
 */
final class ScheduleForm {

  /** Sent back by the API, never accepted. */
  private static final Set<String> READ_ONLY = Set.of("id", "jobRegistered", "nextRunAt", "lastRunAt",
      "lastStatus", "retryAttempt", "runningOn", "createdAt", "createdBy", "updatedAt", "updatedBy");

  static final int MAX_SECONDS = 7 * 24 * 3600;

  /** A parsed body. {@code scope} is set on create, {@code version} on change. */
  record Result(ScheduleFields fields, ScheduleScope scope, Integer version) {
  }

  /** A parsed preview body. */
  record Preview(ScheduleSpec spec, String timeZone, Instant startAt, Instant endAt, int count) {
  }

  private ScheduleForm() {
  }

  /**
   * @param base     the current schedule (PATCH) or ScheduleFields.defaults() (POST)
   * @param creating POST: scope may be given, version may not; PATCH: the reverse
   * @param now      the anchor of an INTERVAL without startAt on create
   * @throws ValidationFailedException listing every problem
   */
  static Result apply(JsonNode body, ScheduleFields base, boolean creating, Instant now) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
    var v = Validation.check();
    var b = new Builder(base);
    ScheduleScope scope = creating ? ScheduleScope.COMPANY : null;
    Integer version = null;

    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      String f = e.getKey();
      JsonNode n = e.getValue();
      switch (f) {
        case "name" -> b.name = text(v, f, n, false, b.name);
        case "description" -> b.description = text(v, f, n, true, b.description);
        case "jobKey" -> b.jobKey = text(v, f, n, false, b.jobKey);
        case "scheduleType" -> b.scheduleType = enumValue(v, f, n, ScheduleType.class, b.scheduleType);
        case "cronExpression" -> b.cronExpression = text(v, f, n, true, b.cronExpression);
        case "interval" -> b.interval = text(v, f, n, true, b.interval);
        case "timeZone" -> b.timeZone = text(v, f, n, false, b.timeZone);
        case "parameters" -> {
          if (n.isNull()) {
            b.parameters = null;
          } else if (n.isObject()) {
            b.parameters = n.toString();
          } else {
            v.add(f, "INVALID_TYPE", "must be a JSON object or null");
          }
        }
        case "isEnabled" -> {
          if (n.isBoolean()) {
            b.enabled = n.booleanValue();
          } else {
            v.add(f, "INVALID_TYPE", "must be true or false");
          }
        }
        case "startAt" -> b.startAt = instant(v, f, n, b.startAt);
        case "endAt" -> b.endAt = instant(v, f, n, b.endAt);
        case "misfirePolicy" -> b.misfirePolicy = enumValue(v, f, n, MisfirePolicy.class, b.misfirePolicy);
        case "misfireThresholdSeconds" -> b.misfireThresholdSeconds = integer(v, f, n, false, 0, MAX_SECONDS,
            b.misfireThresholdSeconds);
        case "timeoutSeconds" -> b.timeoutSeconds = integer(v, f, n, true, 1, MAX_SECONDS, b.timeoutSeconds);
        case "maxRetries" -> b.maxRetries = integer(v, f, n, false, 0, 20, b.maxRetries);
        case "retryBackoffSeconds" -> b.retryBackoffSeconds = integer(v, f, n, false, 1, 24 * 3600,
            b.retryBackoffSeconds);
        case "scope" -> {
          if (creating) {
            scope = enumValue(v, f, n, ScheduleScope.class, scope);
          } else {
            v.add(f, "READ_ONLY", "cannot be changed; create a new schedule instead");
          }
        }
        case "version" -> {
          if (creating) {
            v.add(f, "READ_ONLY", "is set by the server");
          } else {
            version = integer(v, f, n, false, 0, Integer.MAX_VALUE, null);
          }
        }
        default -> v.add(f, READ_ONLY.contains(f) ? "READ_ONLY" : "UNKNOWN_FIELD",
            READ_ONLY.contains(f) ? "is set by the server" : "unknown field '" + f + "'");
      }
    }

    if (creating) {
      v.required("name", b.name).required("jobKey", b.jobKey).required("scheduleType", b.scheduleType);
    } else if (!body.has("version")) {
      v.add("version", "REQUIRED", "send the version you last read");
    }
    v.maxLength("name", b.name, 100).maxLength("description", b.description, 255)
        .maxLength("jobKey", b.jobKey, 100);
    if (b.scheduleType != null) {
      Instant anchor = b.startAt != null ? b.startAt : now;
      spec(v, b.scheduleType, b.cronExpression, b.interval, b.timeZone, anchor);
    }
    if (b.startAt != null && b.endAt != null && !b.endAt.isAfter(b.startAt)) {
      v.add("endAt", "BEFORE_START", "must be after startAt");
    }
    v.throwIfInvalid();
    return new Result(b.build(), scope, version);
  }

  /** A preview of an unsaved schedule: scheduleType, cronExpression, interval, timeZone, startAt, endAt, count. */
  static Preview preview(JsonNode body, Instant now) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
    var v = Validation.check();
    ScheduleType type = null;
    String cron = null;
    String interval = null;
    String zone = ScheduleFields.DEFAULT_TIME_ZONE;
    Instant startAt = null;
    Instant endAt = null;
    Integer count = 10;
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      String f = e.getKey();
      JsonNode n = e.getValue();
      switch (f) {
        case "scheduleType" -> type = enumValue(v, f, n, ScheduleType.class, null);
        case "cronExpression" -> cron = text(v, f, n, true, null);
        case "interval" -> interval = text(v, f, n, true, null);
        case "timeZone" -> zone = text(v, f, n, false, zone);
        case "startAt" -> startAt = instant(v, f, n, null);
        case "endAt" -> endAt = instant(v, f, n, null);
        case "count" -> count = integer(v, f, n, false, 1, 50, count);
        default -> v.add(f, "UNKNOWN_FIELD", "unknown field '" + f + "'");
      }
    }
    v.required("scheduleType", type);
    ScheduleSpec spec = type == null ? null
        : spec(v, type, cron, interval, zone, startAt != null ? startAt : now);
    v.throwIfInvalid();
    return new Preview(spec, zone, startAt, endAt, count);
  }

  /** The spec, or null with the problems added to {@code v}. */
  private static ScheduleSpec spec(Validation v, ScheduleType type, String cron, String interval, String zone,
      Instant anchor) {
    ScheduleSpec spec = null;
    try {
      spec = ScheduleSpec.of(type, cron, interval, zone, anchor);
    } catch (ValidationFailedException e) {
      for (FieldError fe : e.fieldErrors()) {
        v.add(fe.field(), fe.code(), fe.message());
      }
    }
    if (type == ScheduleType.INTERVAL && zone != null) { // ScheduleSpec only checks it for CRON
      try {
        ZoneId.of(zone);
      } catch (DateTimeException e) {
        v.add("timeZone", "INVALID_TIME_ZONE", "must be an IANA time zone such as America/Toronto");
      }
    }
    return spec;
  }

  // ---- typed field readers: on a wrong type they report it and keep the old value ----

  private static String text(Validation v, String field, JsonNode n, boolean nullable, String current) {
    if (n.isNull()) {
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
    String s = n.textValue().strip();
    if (s.isEmpty()) {
      if (!nullable) {
        v.add(field, "REQUIRED", "is required");
        return current;
      }
      return null;
    }
    return s;
  }

  private static Integer integer(Validation v, String field, JsonNode n, boolean nullable, int min, int max,
      Integer current) {
    if (n.isNull()) {
      if (!nullable) {
        v.add(field, "REQUIRED", "is required");
        return current;
      }
      return null;
    }
    if (!n.canConvertToInt() || !n.isIntegralNumber()) {
      v.add(field, "INVALID_TYPE", "must be a whole number");
      return current;
    }
    int value = n.intValue();
    if (value < min || value > max) {
      v.add(field, "OUT_OF_RANGE", "must be between " + min + " and " + max);
      return current;
    }
    return value;
  }

  private static Instant instant(Validation v, String field, JsonNode n, Instant current) {
    if (n.isNull()) {
      return null;
    }
    try {
      if (n.isTextual()) {
        return Instant.parse(n.textValue());
      }
    } catch (DateTimeException e) {
      // reported below
    }
    v.add(field, "INVALID_FORMAT", "must be an ISO-8601 instant such as 2026-12-01T12:00:00Z");
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
    var names = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    v.add(field, "INVALID_VALUE", "must be one of " + names);
    return current;
  }

  /** Mutable copy of ScheduleFields while a body is applied. */
  private static final class Builder {
    String name;
    String description;
    String jobKey;
    ScheduleType scheduleType;
    String cronExpression;
    String interval;
    String timeZone;
    String parameters;
    boolean enabled;
    Instant startAt;
    Instant endAt;
    MisfirePolicy misfirePolicy;
    Integer misfireThresholdSeconds;
    Integer timeoutSeconds;
    Integer maxRetries;
    Integer retryBackoffSeconds;

    Builder(ScheduleFields f) {
      name = f.name();
      description = f.description();
      jobKey = f.jobKey();
      scheduleType = f.scheduleType();
      cronExpression = f.cronExpression();
      interval = f.interval();
      timeZone = f.timeZone();
      parameters = f.parameters();
      enabled = f.enabled();
      startAt = f.startAt();
      endAt = f.endAt();
      misfirePolicy = f.misfirePolicy();
      misfireThresholdSeconds = f.misfireThresholdSeconds();
      timeoutSeconds = f.timeoutSeconds();
      maxRetries = f.maxRetries();
      retryBackoffSeconds = f.retryBackoffSeconds();
    }

    ScheduleFields build() {
      return new ScheduleFields(name, description, jobKey, scheduleType, cronExpression, interval, timeZone,
          parameters, enabled, startAt, endAt, misfirePolicy, misfireThresholdSeconds, timeoutSeconds, maxRetries,
          retryBackoffSeconds);
    }
  }
}

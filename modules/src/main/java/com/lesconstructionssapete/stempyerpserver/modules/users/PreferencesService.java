package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.modules.users.PreferenceRepository.StoredPreference;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.Preference;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.PreferenceList;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.DeletePreferenceOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ListPreferencesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.PutPreferenceOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;

/**
 * ORCHESTRATOR for the signed-in user's own preferences (docs/ARCHITECTURE.md
 * §27): JSON values under keys the client chooses, which the server stores
 * without reading. It only checks the key, that there is a value, its size
 * and the number of keys. Not company-scoped.
 */
public class PreferencesService {

  public static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:@-]{1,100}");
  /** The value written as compact JSON, in UTF-8 bytes. */
  public static final int MAX_VALUE_BYTES = 64 * 1024;
  public static final int MAX_KEYS = 500;

  /** Reads stored values back with the project's settings (decimals kept exact). */
  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();

  private final UnitOfWorkFactory uowFactory;
  private final Clock clock;
  private final ListPreferencesOperation list;
  private final PutPreferenceOperation put;
  private final DeletePreferenceOperation delete;

  public PreferencesService(UnitOfWorkFactory uowFactory, Clock clock, ListPreferencesOperation list,
      PutPreferenceOperation put, DeletePreferenceOperation delete) {
    this.uowFactory = uowFactory;
    this.clock = clock;
    this.list = list;
    this.put = put;
    this.delete = delete;
  }

  public PreferenceList list(long userId) {
    List<StoredPreference> rows = uowFactory.executeReadOnly(uow -> list.execute(uow, userId));
    return new PreferenceList(rows.stream().map(PreferencesService::toDto).toList());
  }

  /** PUT body {"value": any JSON but null}. Answers the preference as stored. */
  public Preference put(long userId, String key, JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
    var v = Validation.check();
    checkKey(v, key);
    JsonNode value = null;
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      if (e.getKey().equals("value")) {
        value = e.getValue();
      } else {
        v.add(e.getKey(), "UNKNOWN_FIELD", "is not a field you can change here");
      }
    }
    String valueJson = null;
    if (value == null || value.isNull()) {
      v.add("value", "REQUIRED", "is required (DELETE removes a preference)");
    } else {
      valueJson = value.toString();
      if (valueJson.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
        v.add("value", "VALUE_TOO_LARGE", "must be at most " + MAX_VALUE_BYTES + " bytes written as JSON",
            Map.of("max", MAX_VALUE_BYTES));
      }
    }
    v.throwIfInvalid();
    Instant now = clock.instant();
    String json = valueJson;
    uowFactory.run(uow -> put.execute(uow, new PutPreferenceOperation.Input(userId, key, json, now)));
    return new Preference(key, value, now);
  }

  /** Removing a key the user doesn't have is fine (the client resets to its default this way). */
  public void delete(long userId, String key) {
    var v = Validation.check();
    checkKey(v, key);
    v.throwIfInvalid();
    uowFactory.run(uow -> delete.execute(uow, new DeletePreferenceOperation.Input(userId, key)));
  }

  private static void checkKey(Validation v, String key) {
    v.required("key", key).matches("key", key, KEY);
  }

  private static Preference toDto(StoredPreference row) {
    try {
      return new Preference(row.key(), JSON.readTree(row.valueJson()), row.updatedAt());
    } catch (JsonProcessingException e) {
      // The column's CHECK (JSON_VALID) makes this impossible short of a hand edit.
      throw new IllegalStateException("Stored preference " + row.key() + " is not valid JSON", e);
    }
  }
}

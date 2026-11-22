package com.lesconstructionssapete.stempyerpserver.platform.config;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only key/value configuration with typed getters. Where the values come
 * from (properties files, .env, environment, a test's map) is decided by
 * whoever builds it; config records read themselves from it
 * (DatabaseConfig.from, AuthSettings.from...).
 *
 * A blank value counts as absent. Getters without a default fail with the key
 * name in the message, and so does a value of the wrong type.
 */
public final class Settings {

  private final Map<String, String> values;

  private Settings(Map<String, String> values) {
    this.values = Map.copyOf(values);
  }

  public static Settings of(Map<String, String> values) {
    return new Settings(values);
  }

  public static Settings empty() {
    return new Settings(Map.of());
  }

  /** Loads these properties files from the classpath; later files win. */
  public static Settings fromClasspath(String... files) {
    Map<String, String> values = new HashMap<>();
    for (String file : files) {
      Properties properties = new Properties();
      try (InputStream in = Settings.class.getClassLoader().getResourceAsStream(file)) {
        if (in == null) {
          throw new IllegalStateException(file + " not found on the classpath");
        }
        properties.load(in);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to load " + file, e);
      }
      properties.stringPropertyNames().forEach(k -> values.put(k, properties.getProperty(k)));
    }
    return new Settings(values);
  }

  /** These settings, with {@code overrides} winning on shared keys. */
  public Settings overriddenBy(Settings overrides) {
    Map<String, String> merged = new HashMap<>(values);
    merged.putAll(overrides.values);
    return new Settings(merged);
  }

  // ---- lookups ----

  public Optional<String> find(String key) {
    String v = values.get(key);
    return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
  }

  public String get(String key) {
    return find(key).orElseThrow(() -> new IllegalStateException("Missing required setting: " + key));
  }

  public String get(String key, String defaultValue) {
    return find(key).orElse(defaultValue);
  }

  public int getInt(String key) {
    return parse(key, get(key), Integer::parseInt, "an integer");
  }

  public int getInt(String key, int defaultValue) {
    return find(key).map(v -> parse(key, v, Integer::parseInt, "an integer")).orElse(defaultValue);
  }

  public long getLong(String key, long defaultValue) {
    return find(key).map(v -> parse(key, v, Long::parseLong, "an integer")).orElse(defaultValue);
  }

  /** ISO-8601, e.g. PT8H (8 hours), P7D (7 days). */
  public Duration getDuration(String key, Duration defaultValue) {
    return find(key).map(v -> parse(key, v, Duration::parse, "an ISO-8601 duration such as PT8H"))
        .orElse(defaultValue);
  }

  /**
   * Settings under {@code prefix}, keyed by the rest of the name:
   * byPrefix("database.url.params") turns database.url.params.useSSL=false
   * into useSSL -> false.
   */
  public Map<String, String> byPrefix(String prefix) {
    String dotted = prefix.endsWith(".") ? prefix : prefix + ".";
    return values.entrySet().stream()
        .filter(e -> e.getKey().startsWith(dotted))
        .collect(Collectors.toMap(e -> e.getKey().substring(dotted.length()), Map.Entry::getValue));
  }

  private static <T> T parse(String key, String value, Function<String, T> parser, String expected) {
    try {
      return parser.apply(value);
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new IllegalStateException("Setting " + key + " must be " + expected + ", got '" + value + "'", e);
    }
  }

  /** Keys only: values may be secrets. */
  @Override
  public String toString() {
    return "Settings" + values.keySet();
  }
}

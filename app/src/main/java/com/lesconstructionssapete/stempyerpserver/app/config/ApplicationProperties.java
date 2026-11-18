package com.lesconstructionssapete.stempyerpserver.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * Non-secret settings from the classpath: application.properties (app module)
 * and db.properties (platform module). Secrets live in .env
 * (ApplicationEnvironment).
 */
public final class ApplicationProperties {

  private static final String[] FILES = { "application.properties", "db.properties" };

  private static final Properties PROPERTIES = load();

  private ApplicationProperties() {
  }

  public static String get(String key) {
    return PROPERTIES.getProperty(key);
  }

  public static String get(String key, String defaultValue) {
    return PROPERTIES.getProperty(key, defaultValue);
  }

  public static int getInt(String key) {
    return Integer.parseInt(getRequired(key));
  }

  public static int getInt(String key, int defaultValue) {
    String value = get(key);

    return value == null
        ? defaultValue
        : Integer.parseInt(value);
  }

  public static boolean getBoolean(String key) {
    return Boolean.parseBoolean(getRequired(key));
  }

  public static boolean getBoolean(String key, boolean defaultValue) {
    String value = get(key);

    return value == null
        ? defaultValue
        : Boolean.parseBoolean(value);
  }

  public static String getRequired(String key) {
    String value = get(key);

    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing required application property: " + key);
    }

    return value;
  }

  /**
   * Properties under {@code prefix}, keyed by the rest of the name:
   * getByPrefix("database.url.params") turns
   * database.url.params.useSSL=false into useSSL -> false.
   */
  public static Map<String, String> getByPrefix(String prefix) {
    String dotted = prefix.endsWith(".") ? prefix : prefix + ".";
    return PROPERTIES.stringPropertyNames().stream()
        .filter(name -> name.startsWith(dotted))
        .collect(
            java.util.stream.Collectors.toMap(
                name -> name.substring(dotted.length()),
                PROPERTIES::getProperty));
  }

  private static Properties load() {
    Properties properties = new Properties();
    for (String file : FILES) {
      try (InputStream input = ApplicationProperties.class
          .getClassLoader()
          .getResourceAsStream(file)) {

        if (input == null) {
          throw new IllegalStateException(file + " not found on the classpath");
        }
        properties.load(input);

      } catch (IOException e) {
        throw new IllegalStateException("Failed to load " + file, e);
      }
    }
    return properties;
  }

}

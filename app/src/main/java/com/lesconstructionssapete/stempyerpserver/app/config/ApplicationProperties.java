package com.lesconstructionssapete.stempyerpserver.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public final class ApplicationProperties {

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

  public static Map<String, String> getByPrefix(String prefix) {
    return PROPERTIES.stringPropertyNames().stream()
        .filter(name -> name.startsWith(prefix))
        .collect(
            java.util.stream.Collectors.toMap(
                name -> name,
                PROPERTIES::getProperty));
  }

  private static Properties load() {
    Properties properties = new Properties();

    try (InputStream input = ApplicationProperties.class
        .getClassLoader()
        .getResourceAsStream("application.properties")) {

      if (input == null) {
        throw new IllegalStateException(
            "application.properties not found");
      }
      properties.load(input);

    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to load application.properties", e);
    }
    return properties;
  }

}

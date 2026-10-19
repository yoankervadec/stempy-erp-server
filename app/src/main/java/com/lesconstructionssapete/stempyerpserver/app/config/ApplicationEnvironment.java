package com.lesconstructionssapete.stempyerpserver.app.config;

import io.github.cdimascio.dotenv.Dotenv;

public final class ApplicationEnvironment {

  private static final Dotenv DOTENV = Dotenv.load();

  private ApplicationEnvironment() {
  }

  public static String get(String key) {
    return DOTENV.get(key);
  }

  public static String get(String key, String defaultValue) {
    return DOTENV.get(key, defaultValue);
  }

  public static int getInt(String key) {
    return Integer.parseInt(get(key));
  }

  public static int getInt(String key, int defaultValue) {
    String value = get(key);

    return value == null
        ? defaultValue
        : Integer.parseInt(value);
  }

  public static boolean getBoolean(String key) {
    return Boolean.parseBoolean(get(key));
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
          "Missing required environment variable: " + key);
    }

    return value;
  }

}

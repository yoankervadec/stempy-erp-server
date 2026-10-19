package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Plain connection settings. The app module builds this (from .env /
 * environment),
 * so platform never needs to know where the values come from.
 */
public record DatabaseConfig(
    String jdbcUrl,
    String user,
    String password,
    int maxPoolSize,
    int minIdle,
    long connectionTimeoutMs,
    long leakDetectionThresholdMs // 0 = disabled
) {

  public DatabaseConfig {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    Objects.requireNonNull(user, "username");
    Objects.requireNonNull(password, "password");
    if (maxPoolSize < 1)
      throw new IllegalArgumentException("maxPoolSize must be >= 1");
  }

  /** Sensible defaults for development. */
  public static DatabaseConfig of(
      String host,
      int port,
      String name,
      Map<String, String> params,
      String username,
      String password) {

    String jdbcUrl = buildJdbcUrl(host, port, name, params);

    return new DatabaseConfig(
        jdbcUrl,
        username,
        password,
        10,
        2,
        5_000,
        0);
  }

  // Builds the JDBC URL from the host, port, database name, and query parameters.
  private static String buildJdbcUrl(
      String host,
      int port,
      String name,
      Map<String, String> params) {

    String jdbcUrl = "jdbc:mysql://%s:%d/%s".formatted(
        host,
        port,
        name);

    if (!params.isEmpty()) {
      StringJoiner query = new StringJoiner("&");
      params.forEach((key, value) -> query.add(key + "=" + value));
      jdbcUrl += "?" + query;
    }

    return jdbcUrl;
  }

  // Never leak the password into logs.
  @Override
  public String toString() {
    return "DatabaseConfig[jdbcUrl=" + jdbcUrl + ", username=" + user
        + ", maxPoolSize=" + maxPoolSize + "]";
  }

}

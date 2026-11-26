package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;

/**
 * Plain connection settings. Usually read with from(Settings); of(...) gives
 * development defaults to override with withPool(...) /
 * withDriverProperties(...).
 *
 * @param driverProperties   JDBC driver properties set on the pool's data
 *                           source (e.g. MySQL's cachePrepStmts)
 * @param autonomousPoolSize connections of the separate pool behind
 *                           UnitOfWorkFactory.autonomous()
 */
public record DatabaseConfig(
    String jdbcUrl,
    String user,
    String password,
    String poolName,
    int maxPoolSize,
    int minIdle,
    long connectionTimeoutMs,
    long leakDetectionThresholdMs, // 0 = disabled
    Map<String, String> driverProperties,
    int autonomousPoolSize) {

  /** Standard MySQL Connector/J performance settings. */
  public static final Map<String, String> DEFAULT_MYSQL_DRIVER_PROPERTIES = Map.of(
      "cachePrepStmts", "true",
      "prepStmtCacheSize", "250",
      "prepStmtCacheSqlLimit", "2048",
      "useServerPrepStmts", "true",
      "rewriteBatchedStatements", "true");

  public DatabaseConfig {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    Objects.requireNonNull(user, "username");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(poolName, "poolName");
    driverProperties = Map.copyOf(Objects.requireNonNull(driverProperties, "driverProperties"));
    if (maxPoolSize < 1)
      throw new IllegalArgumentException("maxPoolSize must be >= 1");
    if (minIdle < 0 || minIdle > maxPoolSize)
      throw new IllegalArgumentException("minIdle must be between 0 and maxPoolSize");
    if (connectionTimeoutMs < 250)
      throw new IllegalArgumentException("connectionTimeoutMs must be >= 250 (HikariCP minimum)");
    if (leakDetectionThresholdMs < 0)
      throw new IllegalArgumentException("leakDetectionThresholdMs must be >= 0");
    if (autonomousPoolSize < 1)
      throw new IllegalArgumentException("autonomousPoolSize must be >= 1");
  }

  /**
   * Environment variables that override a database.url.params.* key, for
   * production (e.g. DATABASE_USE_SSL=true): environment variable names can't
   * hold the dots of the property keys.
   */
  private static final Map<String, String> URL_PARAM_OVERRIDES = Map.of(
      "DATABASE_USE_SSL", "useSSL",
      "DATABASE_SSL_MODE", "sslMode",
      "DATABASE_ALLOW_PUBLIC_KEY_RETRIEVAL", "allowPublicKeyRetrieval");

  /**
   * Connection from DATABASE_HOST, _PORT, _NAME, _USER, _PASSWORD (.env or
   * environment); URL params, pool and driver settings from the database.*
   * keys (db.properties), each URL param overridable by the environment
   * variables in URL_PARAM_OVERRIDES. Anything absent keeps the of(...)
   * default.
   */
  public static DatabaseConfig from(Settings s) {
    Map<String, String> params = new LinkedHashMap<>(s.byPrefix("database.url.params"));
    URL_PARAM_OVERRIDES.forEach((env, param) -> s.find(env).ifPresent(v -> params.put(param, v)));
    DatabaseConfig d = of(
        s.get("DATABASE_HOST"),
        s.getInt("DATABASE_PORT"),
        s.get("DATABASE_NAME"),
        params,
        s.get("DATABASE_USER"),
        s.get("DATABASE_PASSWORD"));
    Map<String, String> driverProperties = s.byPrefix("database.mysql.performance.settings");
    return d
        .withPool(
            s.get("database.pool.name", d.poolName()),
            s.getInt("database.pool.maxSize", d.maxPoolSize()),
            s.getInt("database.pool.minIdle", d.minIdle()),
            s.getLong("database.pool.connectionTimeoutMs", d.connectionTimeoutMs()),
            s.getLong("database.pool.leakDetectionThresholdMs", d.leakDetectionThresholdMs()))
        .withDriverProperties(driverProperties.isEmpty() ? d.driverProperties() : driverProperties)
        .withAutonomousPoolSize(s.getInt("database.autonomousPool.maxSize", d.autonomousPoolSize()));
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
        "stempy-erp",
        10,
        2,
        5_000,
        0,
        DEFAULT_MYSQL_DRIVER_PROPERTIES,
        4);
  }

  /** The same connection with different pool settings. */
  public DatabaseConfig withPool(String poolName, int maxPoolSize, int minIdle, long connectionTimeoutMs,
      long leakDetectionThresholdMs) {
    return new DatabaseConfig(jdbcUrl, user, password, poolName, maxPoolSize, minIdle, connectionTimeoutMs,
        leakDetectionThresholdMs, driverProperties, autonomousPoolSize);
  }

  /** The same connection with these driver properties instead of the current ones. */
  public DatabaseConfig withDriverProperties(Map<String, String> driverProperties) {
    return new DatabaseConfig(jdbcUrl, user, password, poolName, maxPoolSize, minIdle, connectionTimeoutMs,
        leakDetectionThresholdMs, driverProperties, autonomousPoolSize);
  }

  /** The same connection with a different autonomous pool size. */
  public DatabaseConfig withAutonomousPoolSize(int autonomousPoolSize) {
    return new DatabaseConfig(jdbcUrl, user, password, poolName, maxPoolSize, minIdle, connectionTimeoutMs,
        leakDetectionThresholdMs, driverProperties, autonomousPoolSize);
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
        + ", poolName=" + poolName + ", maxPoolSize=" + maxPoolSize + ", autonomousPoolSize=" + autonomousPoolSize
        + "]";
  }

}

package com.lesconstructionssapete.stempyerpserver.platform.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Builds the HikariCP pools. Returns HikariDataSource (not just DataSource)
 * so the app can call close() on shutdown.
 *
 * Every connection runs REPEATABLE READ (numbering and GL locking rely on it)
 * with its session time zone forced to UTC by the URL parameters in
 * db.properties (connectionTimeZone, forceConnectionTimeZoneToSession).
 */
public final class DataSources {

  private DataSources() {
  }

  /** The main pool: requests, jobs, outbox deliveries. */
  public static HikariDataSource create(DatabaseConfig cfg) {
    return create(cfg, cfg.poolName(), cfg.maxPoolSize(), Math.min(cfg.minIdle(), cfg.maxPoolSize()));
  }

  /**
   * The pool for UnitOfWorkFactory.autonomous(): short transactions opened
   * while the caller holds a connection of the main pool.
   */
  public static HikariDataSource createAutonomous(DatabaseConfig cfg) {
    return create(cfg, cfg.poolName() + "-autonomous", cfg.autonomousPoolSize(), 0);
  }

  private static HikariDataSource create(DatabaseConfig cfg, String poolName, int maxSize, int minIdle) {
    HikariConfig hc = new HikariConfig();
    hc.setPoolName(poolName);
    hc.setJdbcUrl(cfg.jdbcUrl());
    hc.setUsername(cfg.user());
    hc.setPassword(cfg.password());

    hc.setMaximumPoolSize(maxSize);
    hc.setMinimumIdle(minIdle);
    hc.setConnectionTimeout(cfg.connectionTimeoutMs());
    if (cfg.leakDetectionThresholdMs() > 0) {
      // Logs a stack trace if a connection is held longer than this: catches a
      // missing close().
      hc.setLeakDetectionThreshold(cfg.leakDetectionThresholdMs());
    }

    // Connections come out of the pool ready for a transaction. Hikari also rolls
    // back
    // any uncommitted work when a connection is returned, as a last line of
    // defense.
    hc.setAutoCommit(false);
    // MySQL's default, but stated: never depend on the server's configuration for it.
    hc.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");

    // Driver settings, e.g. MySQL Connector/J prepared-statement caching
    cfg.driverProperties().forEach(hc::addDataSourceProperty);

    return new HikariDataSource(hc);
  }

}

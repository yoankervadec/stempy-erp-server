package com.lesconstructionssapete.stempyerpserver.platform.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Builds the HikariCP pool. Returns HikariDataSource (not just DataSource)
 * so the app can call close() on shutdown.
 *
 * JDBC URL tip for MySQL, to avoid timezone surprises with timestamps:
 * jdbc:mysql://host:3306/stempy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
 */
public final class DataSources {

  private DataSources() {
  }

  public static HikariDataSource create(DatabaseConfig cfg) {
    HikariConfig hc = new HikariConfig();
    hc.setPoolName("stempy-erp");
    hc.setJdbcUrl(cfg.jdbcUrl());
    hc.setUsername(cfg.user());
    hc.setPassword(cfg.password());

    hc.setMaximumPoolSize(cfg.maxPoolSize());
    hc.setMinimumIdle(cfg.minIdle());
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

    // Standard MySQL Connector/J performance settings
    hc.addDataSourceProperty("cachePrepStmts", "true");
    hc.addDataSourceProperty("prepStmtCacheSize", "250");
    hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
    hc.addDataSourceProperty("useServerPrepStmts", "true");
    hc.addDataSourceProperty("rewriteBatchedStatements", "true");

    return new HikariDataSource(hc);
  }

}

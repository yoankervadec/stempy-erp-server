package com.lesconstructionssapete.stempyerpserver.platform.db;

import org.flywaydb.core.Flyway;

/**
 * Utility class for performing database migrations using Flyway.
 */
public final class DatabaseMigrator {

  private DatabaseMigrator() {
  }

  public static void migrate(String jdbcUrl, String user, String password) {
    Flyway.configure()
        .dataSource(jdbcUrl, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

}

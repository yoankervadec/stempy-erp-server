package com.lesconstructionssapete.stempyerpserver.platform.db;

import org.flywaydb.core.Flyway;

/**
 * Utility class for performing database migrations using Flyway.
 */
public final class DatabaseMigrator {

  private DatabaseMigrator() {
  }

  public static void migrate(DatabaseConfig db) {
    Flyway.configure()
        .dataSource(db.jdbcUrl(), db.user(), db.password())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

}

package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;

/**
 * Brings the schema up to date with Flyway, then checks that every table and
 * text column uses utf8mb4: a table created on a server whose default is
 * another character set would silently mangle names and descriptions, so the
 * server refuses to start on one instead.
 */
public final class DatabaseMigrator {

  static final String CHARSET = "utf8mb4";

  private DatabaseMigrator() {
  }

  public static void migrate(DatabaseConfig db) {
    Flyway.configure()
        .dataSource(db.jdbcUrl(), db.user(), db.password())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    checkCharacterSet(db);
  }

  private static void checkCharacterSet(DatabaseConfig db) {
    List<String> wrong = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password());
        PreparedStatement ps = c.prepareStatement("""
            SELECT t.TABLE_NAME, NULL, t.TABLE_COLLATION
              FROM information_schema.TABLES t
             WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'
               AND t.TABLE_COLLATION NOT LIKE CONCAT(?, '\\_%')
            UNION ALL
            SELECT c.TABLE_NAME, c.COLUMN_NAME, c.CHARACTER_SET_NAME
              FROM information_schema.COLUMNS c
             WHERE c.TABLE_SCHEMA = DATABASE() AND c.CHARACTER_SET_NAME IS NOT NULL
               AND c.CHARACTER_SET_NAME <> ?
             ORDER BY 1, 2""")) {
      ps.setString(1, CHARSET);
      ps.setString(2, CHARSET);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String column = rs.getString(2);
          wrong.add(rs.getString(1) + (column == null ? "" : "." + column) + " (" + rs.getString(3) + ")");
        }
      }
    } catch (SQLException e) {
      throw new DataAccessException("Could not check the schema's character set", e);
    }
    if (!wrong.isEmpty()) {
      throw new IllegalStateException("Every table and text column must use " + CHARSET + ", but these don't: "
          + String.join(", ", wrong) + ". Convert them (ALTER TABLE ... CONVERT TO CHARACTER SET " + CHARSET
          + " COLLATE utf8mb4_0900_ai_ci) after checking the data, then restart.");
    }
  }
}

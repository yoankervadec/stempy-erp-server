package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * JDBC TIMESTAMP <-> Instant, always through UTC, so values never depend on
 * the JVM or MySQL session time zone. The one place this conversion lives:
 * FieldType.INSTANT and Jdbc both use it.
 */
public final class Timestamps {

  private Timestamps() {
  }

  public static void bind(PreparedStatement ps, int index, Instant value) throws SQLException {
    ps.setObject(index, value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
  }

  public static Instant read(ResultSet rs, String column) throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }
}

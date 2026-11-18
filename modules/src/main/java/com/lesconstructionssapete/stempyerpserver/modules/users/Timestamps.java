package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC TIMESTAMP <-> Instant, the same way the platform's FieldType does it. */
final class Timestamps {

  private Timestamps() {
  }

  static void set(PreparedStatement ps, int index, Instant value) throws SQLException {
    ps.setObject(index, value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
  }

  static Instant get(ResultSet rs, String column) throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }
}

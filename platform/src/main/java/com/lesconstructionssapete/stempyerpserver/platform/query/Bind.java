package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;

/** One bound parameter: a value plus the type that knows how to bind it. */
public record Bind(FieldType<?> type, Object value) {

  void apply(PreparedStatement ps, int index) throws SQLException {
    type.bindObject(ps, index, value);
  }
}

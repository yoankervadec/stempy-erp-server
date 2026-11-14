package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * Runs a translated SELECT on the request's UnitOfWork and maps rows with the
 * EntityDef.
 */
public final class QueryExecutor {

  public <E> List<E> list(UnitOfWork uow, SQLQuery<E> query) {
    try (PreparedStatement ps = uow.connection().prepareStatement(query.sql())) {
      int index = 1;
      for (Bind bind : query.binds()) {
        bind.apply(ps, index++);
      }
      try (ResultSet rs = ps.executeQuery()) {
        List<E> out = new ArrayList<>();
        while (rs.next()) {
          out.add(query.entity().mapRow(rs, query.selected()));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  /** Entities as API maps containing only the fields the user may read. */
  public <E> List<Map<String, Object>> listAsMaps(UnitOfWork uow, SQLQuery<E> query) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (E entity : list(uow, query)) {
      out.add(query.entity().toMap(entity, query.visible()));
    }
    return out;
  }
}

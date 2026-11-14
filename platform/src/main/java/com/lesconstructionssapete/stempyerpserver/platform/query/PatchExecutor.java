package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;

/**
 * Runs a SQLUpdate. Throws ResourceNotFoundException (404) or ConflictException
 * (409) when no row changed.
 */
public final class PatchExecutor {

  public <E> void execute(UnitOfWork uow, SQLUpdate<E> update) {
    try {
      int changed;
      try (PreparedStatement ps = uow.connection().prepareStatement(update.sql())) {
        bindAll(ps, update.binds());
        changed = ps.executeUpdate();
      }
      if (changed == 1) {
        return;
      }
      boolean exists;
      try (PreparedStatement ps = uow.connection().prepareStatement(update.existsSql())) {
        bindAll(ps, update.existsBinds());
        try (ResultSet rs = ps.executeQuery()) {
          exists = rs.next();
        }
      }
      String entity = update.entity().name();
      if (exists) {
        throw new ConflictException("CONFLICT", entity + " was modified by someone else; reload and retry");
      }
      throw new ResourceNotFoundException("NOT_FOUND", entity + " not found");
    } catch (SQLException e) {
      throw new DataAccessException(e);
    }
  }

  private static void bindAll(PreparedStatement ps, List<Bind> binds) throws SQLException {
    int index = 1;
    for (Bind bind : binds) {
      bind.apply(ps, index++);
    }
  }
}

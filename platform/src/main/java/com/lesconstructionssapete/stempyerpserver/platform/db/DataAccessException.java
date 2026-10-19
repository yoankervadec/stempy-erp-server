package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.sql.SQLException;

/**
 * Unchecked wrapper for SQLException, so repositories, operations and
 * orchestrators
 */
public class DataAccessException extends RuntimeException {

  public DataAccessException(String message, SQLException cause) {
    super(message, cause);
  }

  public DataAccessException(SQLException cause) {
    super(cause.getMessage(), cause);
  }

  /**
   * True for failures that are usually safe to retry the whole transaction on:
   * deadlock / serialization failure (SQLState 40001) and MySQL lock wait timeout
   * (error 1205).
   */
  public boolean isRetryable() {
    return getCause() instanceof SQLException e
        && ("40001".equals(e.getSQLState()) || e.getErrorCode() == 1205);
  }

}

package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One database transaction, backed by one pooled connection.
 *
 * Passed explicitly through orchestrators, operations and repositories.
 * Safe default: if close() is reached without commit(), everything is rolled
 * back.
 *
 * Not thread-safe: one UnitOfWork belongs to one request / job run.
 */
public final class UnitOfWork implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(UnitOfWork.class);

  private enum State {
    ACTIVE,
    COMMITTED,
    ROLLED_BACK,
    CLOSED
  }

  private final Connection connection;
  private State state = State.ACTIVE;

  // Package-private: only UnitOfWorkFactory creates these.
  UnitOfWork(Connection connection) {
    this.connection = connection;
  }

  /**
   * The connection repositories use. Only valid while the transaction is active.
   */
  public Connection connection() {
    requireActive();
    return connection;
  }

  public void commit() {
    requireActive();
    try {
      connection.commit();
      state = State.COMMITTED;
    } catch (SQLException e) {
      // State stays ACTIVE, so close() will attempt a rollback.
      throw new DataAccessException("Commit failed", e);
    }
  }

  public void rollback() {
    requireActive();
    try {
      connection.rollback();
      state = State.ROLLED_BACK;
    } catch (SQLException e) {
      throw new DataAccessException("Rollback failed", e);
    }
  }

  /**
   * Runs work inside a savepoint. If it throws, only the work done since the
   * savepoint
   * is undone, then the exception is rethrown. The caller decides whether to
   * catch it
   * and continue (e.g. "post 50 invoices, skip the ones that fail").
   */
  public <T> T withSavepoint(Supplier<T> work) {
    Savepoint sp = setSavepoint();
    try {
      T result = work.get();
      releaseSavepoint(sp);
      return result;
    } catch (RuntimeException e) {
      try {
        rollbackTo(sp);
      } catch (RuntimeException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw e;
    }
  }

  public void runInSavepoint(Runnable work) {
    withSavepoint(() -> {
      work.run();
      return null;
    });
  }

  private Savepoint setSavepoint() {
    requireActive();
    try {
      return connection.setSavepoint();
    } catch (SQLException e) {
      throw new DataAccessException("Could not create savepoint", e);
    }
  }

  private void rollbackTo(Savepoint sp) {
    try {
      connection.rollback(sp);
    } catch (SQLException e) {
      throw new DataAccessException("Could not roll back to savepoint", e);
    }
  }

  private void releaseSavepoint(Savepoint sp) {
    try {
      connection.releaseSavepoint(sp);
    } catch (SQLException e) {
      throw new DataAccessException("Could not release savepoint", e);
    }
  }

  /**
   * Rolls back if still uncommitted, then returns the connection to the pool.
   * Idempotent.
   */
  @Override
  public void close() {
    if (state == State.CLOSED)
      return;
    try {
      if (state == State.ACTIVE) {
        try {
          connection.rollback();
        } catch (SQLException e) {
          LOGGER.warn("Rollback during close failed", e);
        }
      }
    } finally {
      state = State.CLOSED;
      try {
        connection.close(); // returns to Hikari, doesn't really close
      } catch (SQLException e) {
        LOGGER.warn("Closing connection failed", e);
      }
    }
  }

  private void requireActive() {
    if (state != State.ACTIVE) {
      throw new IllegalStateException("UnitOfWork is " + state + ", not ACTIVE");
    }
  }

}

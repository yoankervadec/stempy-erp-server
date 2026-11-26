package com.lesconstructionssapete.stempyerpserver.platform.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.sql.DataSource;

/**
 * Opens UnitOfWorks. Most callers should use execute()/run(), which handle
 * commit-on-success, rollback-on-exception and returning the connection to the
 * pool.
 * Use begin() directly only when you need manual control (e.g. a scheduler
 * job).
 *
 * <p>Autonomous transactions: code that holds a UnitOfWork and needs a short
 * transaction of its own (a GAPS_ALLOWED number, a new counter, a new GL
 * dimension set) uses {@link #autonomous()}, never this factory. Taking a
 * second connection from the same pool while holding one deadlocks once every
 * connection is held by a caller waiting for a second one; the autonomous
 * pool is separate, and its transactions never need a further connection.
 */
public class UnitOfWorkFactory {

  private final DataSource dataSource;
  private final UnitOfWorkFactory autonomous;

  /** One pool for everything: autonomous() is this factory (tests, CLI tools). */
  public UnitOfWorkFactory(DataSource dataSource) {
    this.dataSource = dataSource;
    this.autonomous = this;
  }

  /**
   * @param autonomousDataSource a separate pool for {@link #autonomous()}
   *                             transactions (DataSources.createAutonomous)
   */
  public UnitOfWorkFactory(DataSource dataSource, DataSource autonomousDataSource) {
    this.dataSource = dataSource;
    this.autonomous = new UnitOfWorkFactory(autonomousDataSource);
  }

  /**
   * For short transactions opened while the caller holds a UnitOfWork. Work run
   * here must not open another transaction itself.
   */
  public UnitOfWorkFactory autonomous() {
    return autonomous;
  }

  /** Manual control: caller must close() it, ideally with try-with-resources. */
  public UnitOfWork begin() {
    return open(false);
  }

  /**
   * For GET endpoints and reports: the server rejects writes on this connection.
   */
  public UnitOfWork beginReadOnly() {
    return open(true);
  }

  /** Runs work in a transaction: commit if it returns, rollback if it throws. */
  public <T> T execute(Function<UnitOfWork, T> work) {
    try (UnitOfWork uow = begin()) {
      T result = work.apply(uow);
      uow.commit();
      return result;
    } // close() rolls back automatically if commit() was never reached
  }

  public void run(Consumer<UnitOfWork> work) {
    execute(uow -> {
      work.accept(uow);
      return null;
    });
  }

  public <T> T executeReadOnly(Function<UnitOfWork, T> work) {
    try (UnitOfWork uow = beginReadOnly()) {
      T result = work.apply(uow);
      uow.commit(); // ends the read transaction cleanly
      return result;
    }
  }

  private UnitOfWork open(boolean readOnly) {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      connection.setAutoCommit(false); // defensive; the pool is already configured this way
      if (readOnly) {
        connection.setReadOnly(true); // Hikari resets this when the connection is returned
      }
      return new UnitOfWork(connection);
    } catch (SQLException e) {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException ignored) {
          // nothing more we can do
        }
      }
      throw new DataAccessException("Could not open unit of work", e);
    }
  }

}

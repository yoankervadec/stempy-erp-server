package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;

/**
 * What a job type does. Lives in its module and, like any other entry point,
 * calls an orchestrator; the scheduler only decides when.
 *
 * The scheduler does not open a transaction around a job: a long job may want
 * to commit in batches. Use {@link #transactional} for the common
 * one-transaction case.
 *
 * Throwing marks the run FAILED (retried if the schedule allows it). A job
 * that loops or waits must check {@link JobContext#isCancelled()} (or call
 * {@link JobContext#throwIfCancelled()}): on timeout or shutdown the scheduler
 * sets it and interrupts the thread.
 */
@FunctionalInterface
public interface Job {

  void execute(JobContext context) throws Exception;

  /** A job body that runs in one transaction. */
  @FunctionalInterface
  interface InTransaction {
    void execute(UnitOfWork uow, JobContext context);
  }

  /** Runs {@code work} in one transaction: committed if it returns, rolled back if it throws. */
  static Job transactional(UnitOfWorkFactory uowFactory, InTransaction work) {
    Objects.requireNonNull(uowFactory, "uowFactory");
    Objects.requireNonNull(work, "work");
    return context -> uowFactory.run(uow -> work.execute(uow, context));
  }
}

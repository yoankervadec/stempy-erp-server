package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

/** Thrown by {@link JobContext#throwIfCancelled()} once the run was told to stop. */
public class JobCancelledException extends RuntimeException {

  public JobCancelledException(String message) {
    super(message);
  }
}

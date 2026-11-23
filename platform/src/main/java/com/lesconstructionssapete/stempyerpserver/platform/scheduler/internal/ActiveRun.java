package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

/**
 * Cancellation of one run on its worker thread. The watchdog (timeout, lease
 * renewal) and stop() may cancel it; the worker calls {@link #finish()} once
 * the job has returned. Both are synchronized, so an interrupt can never land
 * after finish() and hit the next job on the same thread.
 */
final class ActiveRun {

  enum Reason {
    TIMEOUT("ran past its timeout"),
    LEASE_LOST("lost its lease (database unreachable, or another instance took over)"),
    SHUTDOWN("interrupted by server shutdown");

    final String message;

    Reason(String message) {
      this.message = message;
    }
  }

  private final Thread thread;
  private volatile Reason reason;
  private boolean done;
  private volatile long leaseRenewedNanos = System.nanoTime();

  ActiveRun(Thread thread) {
    this.thread = thread;
  }

  void leaseRenewed() {
    leaseRenewedNanos = System.nanoTime();
  }

  /** Time since the lease was last taken or renewed. */
  long nanosSinceLeaseRenewed() {
    return System.nanoTime() - leaseRenewedNanos;
  }

  /** Sets the reason (the first one wins) and interrupts the worker, unless the job already returned. */
  synchronized void cancel(Reason why) {
    if (done || reason != null) {
      return;
    }
    reason = why;
    thread.interrupt();
  }

  /** On the worker thread, after the job returned or threw: no more interrupts, and clears a pending one. */
  synchronized void finish() {
    done = true;
    Thread.interrupted();
  }

  boolean isCancelled() {
    return reason != null;
  }

  /** Null unless cancelled. */
  Reason reason() {
    return reason;
  }
}

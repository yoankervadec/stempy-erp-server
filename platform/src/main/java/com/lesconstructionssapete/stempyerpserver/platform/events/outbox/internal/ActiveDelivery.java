package com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal;

/**
 * Cancellation of one delivery on its worker thread, like the scheduler's
 * ActiveRun. The watchdog (timeout) and stop() may cancel it; the worker calls
 * {@link #finish()} once the handler has returned. Both are synchronized, so
 * an interrupt can never land after finish(), in the recording of the outcome
 * or in the next delivery on the same thread.
 */
final class ActiveDelivery {

  private final Thread thread;
  private boolean done;
  private boolean timedOut;

  ActiveDelivery(Thread thread) {
    this.thread = thread;
  }

  /** The timeout: interrupts the worker, unless the handler already returned. */
  synchronized void timeOut() {
    if (!done) {
      timedOut = true;
      thread.interrupt();
    }
  }

  /** Shutdown: interrupts the worker, unless the handler already returned. */
  synchronized void interrupt() {
    if (!done) {
      thread.interrupt();
    }
  }

  /** On the worker thread, after the handler returned or threw: no more interrupts, and clears a pending one. */
  synchronized void finish() {
    done = true;
    Thread.interrupted();
  }

  synchronized boolean timedOut() {
    return timedOut;
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.events.outbox;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal.OutboxDispatcher;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;

/**
 * Delivers what EventBus async subscriptions wrote to the outbox table
 * (docs/ARCHITECTURE.md §18). Modules never call it: they subscribe with
 * {@code eventBus.subscribeAsync(...)} and publish as usual.
 *
 * Lifecycle:
 * <ol>
 * <li>startup: ModuleHost creates it around the frozen EventBus;</li>
 * <li>server mode only: {@link #start} begins delivering (CLI commands write
 * rows but never deliver them; a server does, later);</li>
 * <li>shutdown: {@link #stop}, before the connection pool is closed.</li>
 * </ol>
 *
 * Several server instances may share a database: each row is claimed by one
 * of them at a time (row locks + a lease).
 */
public final class Outbox {

  private static final Logger LOGGER = LoggerFactory.getLogger(Outbox.class);

  private final EventBus eventBus;
  private OutboxDispatcher dispatcher;
  private volatile OutboxSettings settings; // set by start(); read by the prune job

  public Outbox(EventBus eventBus) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
  }

  /**
   * Starts delivering, unless {@code settings.enabled()} is false (the
   * settings are still kept, for the prune job).
   *
   * @param systemActor who handlers act as
   * @param permissions every declared permission: what handlers get
   */
  public synchronized void start(OutboxSettings settings, UnitOfWorkFactory uowFactory, SystemActor systemActor,
      PermissionRegistry permissions) {
    if (this.settings != null) {
      throw new IllegalStateException("Outbox already started");
    }
    this.settings = Objects.requireNonNull(settings, "settings");
    if (!settings.enabled()) {
      LOGGER.info("Outbox delivery disabled on this instance (outbox.enabled=false)");
      return;
    }
    dispatcher = new OutboxDispatcher(settings, eventBus.asyncSubscriptions(), uowFactory, systemActor,
        permissions.names(), JsonMapperFactory.newObjectMapper());
    dispatcher.start();
  }

  /** The settings it was started with, or the defaults if it never was (CLI mode). */
  public OutboxSettings settings() {
    OutboxSettings s = this.settings;
    return s != null ? s : OutboxSettings.defaults();
  }

  public synchronized boolean isRunning() {
    return dispatcher != null && !dispatcher.isStopped();
  }

  /**
   * Stops claiming, waits up to shutdownGrace for running handlers, then
   * interrupts them and releases their rows. Idempotent; a no-op if never
   * started.
   */
  public synchronized void stop() {
    if (dispatcher != null) {
      dispatcher.stop();
    }
  }
}

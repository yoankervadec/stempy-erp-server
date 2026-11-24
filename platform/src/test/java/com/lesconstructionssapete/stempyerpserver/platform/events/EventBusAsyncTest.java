package com.lesconstructionssapete.stempyerpserver.platform.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxSettings;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxWriter;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged;

/** Async subscriptions on the EventBus, outbox settings and payload JSON: everything that needs no database. */
class EventBusAsyncTest {

  private static final AsyncEventHandler<DomainEvent> NOTHING = (delivery, event) -> {
  };
  /** publish() only hands the UnitOfWork through, so these tests need none. */
  private static final UnitOfWork NO_UOW = null;

  record Enqueued(String key, DomainEvent event) {
  }

  record OtherEvent(long companyId) implements DomainEvent {
  }

  private final List<Enqueued> enqueued = new ArrayList<>();
  private final OutboxWriter writer = (uow, key, event) -> enqueued.add(new Enqueued(key, event));

  private static EntityChanged.Created created() {
    return new EntityChanged.Created("product", 7, 1, 2, Instant.parse("2026-11-24T12:00:00Z"),
        Map.of("name", new EntityChanged.FieldChange(null, "Gravel")));
  }

  @Test
  void publishWritesOneRowPerMatchingAsyncSubscriptionAfterTheSyncHandlers() {
    var bus = new EventBus(writer);
    List<String> order = new ArrayList<>();
    bus.subscribe(EntityChanged.class, (uow, e) -> order.add("sync:" + enqueued.size()));
    bus.subscribeAsync("audit.all", DomainEvent.class, NOTHING);            // supertype
    bus.subscribeAsync("search.entity", EntityChanged.class, (d, e) -> { }); // the type's interface
    bus.subscribeAsync("other.only", OtherEvent.class, (d, e) -> { });       // never matches below
    bus.freeze();

    var event = created();
    bus.publish(NO_UOW, event);

    assertEquals(List.of("sync:0"), order); // synchronous handlers ran first
    assertEquals(List.of(new Enqueued("audit.all", event), new Enqueued("search.entity", event)), enqueued);
    assertEquals(List.of("audit.all", "search.entity", "other.only"),
        bus.asyncSubscriptions().stream().map(AsyncSubscription::key).toList());
  }

  @Test
  void aWriterFailureReachesThePublisher() {
    var bus = new EventBus((uow, key, event) -> {
      throw new IllegalStateException("cannot serialize");
    });
    bus.subscribeAsync("audit.all", DomainEvent.class, NOTHING);
    bus.freeze();
    assertThrows(IllegalStateException.class, () -> bus.publish(NO_UOW, created()));
  }

  @Test
  void aBusWithoutOutboxRefusesAsyncSubscriptions() {
    var bus = new EventBus();
    assertThrows(IllegalStateException.class, () -> bus.subscribeAsync("audit.all", DomainEvent.class, NOTHING));
    bus.freeze();
    assertEquals(List.of(), bus.asyncSubscriptions());
  }

  @Test
  void rejectsDuplicateKeys() {
    var bus = new EventBus(writer);
    bus.subscribeAsync("audit.all", DomainEvent.class, NOTHING);
    assertThrows(IllegalStateException.class, () -> bus.subscribeAsync("audit.all", EntityChanged.class,
        (d, e) -> { }));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "nodot", "Upper.case", "a.", ".a", "a b.c", "a.b_c" })
  void rejectsBadKeys(String key) {
    var bus = new EventBus(writer);
    assertThrows(IllegalArgumentException.class, () -> bus.subscribeAsync(key, DomainEvent.class, NOTHING));
  }

  @Test
  void isFrozenAfterFreeze() {
    var bus = new EventBus(writer);
    assertThrows(IllegalStateException.class, bus::asyncSubscriptions); // not frozen yet
    var sub = bus.subscribeAsync("mail.invoice-posted", DomainEvent.class, NOTHING)
        .withMaxAttempts(3)
        .withTimeout(Duration.ofSeconds(5));
    assertEquals(3, sub.maxAttempts());
    assertEquals(Duration.ofSeconds(5), sub.timeout());
    assertThrows(IllegalArgumentException.class, () -> sub.withMaxAttempts(0));
    assertThrows(IllegalArgumentException.class, () -> sub.withTimeout(Duration.ofHours(2)));
    bus.freeze();
    assertThrows(IllegalStateException.class, () -> bus.subscribeAsync("late.one", DomainEvent.class, NOTHING));
    assertThrows(IllegalStateException.class, () -> sub.withMaxAttempts(5));
  }

  @Test
  void eventsRoundTripThroughThePayloadJson() throws Exception {
    ObjectMapper json = JsonMapperFactory.newObjectMapper();
    // JSON-friendly values (strings, null) come back equal; a BigDecimal would come back as its string.
    var updated = new EntityChanged.Updated("product", 7, 1, 2, Instant.parse("2026-11-24T12:00:00Z"),
        Set.of("price", "description"), Map.of(
            "description", new EntityChanged.FieldChange("Old", "New"),
            "notes", new EntityChanged.FieldChange(null, "first note")));
    String payload = json.writeValueAsString(updated);
    // What the dispatcher does: the stored class name, then readValue.
    Class<?> type = Class.forName(updated.getClass().getName());
    assertEquals(updated, json.readValue(payload, type));
  }

  @Test
  void settingsDefaultsAndValidation() {
    OutboxSettings d = OutboxSettings.defaults();
    assertEquals(Duration.ofSeconds(1), d.pollInterval());
    assertEquals(8, d.defaultMaxAttempts());
    assertEquals(Duration.ofDays(7), d.retention());

    OutboxSettings s = OutboxSettings.from(Settings.of(Map.of("outbox.workerThreads", "2",
        "outbox.enabled", "false", "outbox.instanceId", "node-1")));
    assertEquals(2, s.workerThreads());
    assertEquals(false, s.enabled());
    assertEquals("node-1", s.instanceId());

    assertThrows(IllegalArgumentException.class,
        () -> OutboxSettings.from(Settings.of(Map.of("outbox.workerThreads", "0"))));
    assertThrows(IllegalArgumentException.class,
        () -> OutboxSettings.from(Settings.of(Map.of("outbox.retention", "PT1H"))));
    assertThrows(IllegalArgumentException.class,
        () -> OutboxSettings.from(Settings.of(Map.of("outbox.defaultTimeout", "PT2H"))));
  }
}

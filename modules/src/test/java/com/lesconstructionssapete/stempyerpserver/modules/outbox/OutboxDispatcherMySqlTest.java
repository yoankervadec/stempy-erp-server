package com.lesconstructionssapete.stempyerpserver.modules.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.Outbox;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxDelivery;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxSettings;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxStatus;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal.OutboxRepository;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal.OutboxRow;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * The outbox against real MySQL, with test async subscriptions: rows written
 * (or not) with the publisher's transaction, delivery as SYSTEM, retries with
 * backoff, DEAD messages, timeouts, crash recovery, unreadable rows, two
 * instances, shutdown.
 *
 * The dispatcher polls in the background for the whole class, so each test
 * works only on the messages it publishes (told apart by their tag). The
 * shutdown test runs last.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxDispatcherMySqlTest {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final OutboxSettings SETTINGS = new OutboxSettings(true, Duration.ofMillis(100), 4,
      Duration.ofSeconds(5), 8, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofDays(7),
      Duration.ofDays(90), "instance-a");

  // One event type per behaviour, so each subscription sees only its own.
  record Ping(long companyId, String tag) implements DomainEvent {
  }

  record Flaky(long companyId, String tag) implements DomainEvent {
  }

  record Doomed(long companyId, String tag) implements DomainEvent {
  }

  record Slow(long companyId, String tag) implements DomainEvent {
  }

  record Blocking(long companyId, String tag) implements DomainEvent {
  }

  record StuckInSql(long companyId, String tag) implements DomainEvent {
  }

  /** What a test handler saw. */
  record Seen(String tag, OutboxDelivery delivery, String thread) {
  }

  static final ConcurrentLinkedQueue<Seen> SEEN = new ConcurrentLinkedQueue<>();
  /** The Blocking handler waits while this is false (or until interrupted). */
  static volatile boolean gateOpen;

  /** Subscribes the test handlers, like a real module would. */
  static final class TestSubscribersModule implements ERPModule {
    @Override
    public String name() {
      return "test-outbox";
    }

    @Override
    public void registerEntities(EntityRegistry entityRegistry) {
    }

    @Override
    public void registerEventHandlers(EventBus bus, ServiceRegistry services) {
      bus.subscribeAsync("test.record", Ping.class, (d, e) -> seen(e.tag(), d));
      bus.subscribeAsync("test.flaky", Flaky.class, (d, e) -> {
        seen(e.tag(), d);
        if (d.attempt() < 3) {
          throw new IllegalStateException("flaky attempt " + d.attempt());
        }
      });
      bus.subscribeAsync("test.doomed", Doomed.class, (d, e) -> {
        seen(e.tag(), d);
        throw new IllegalStateException("doomed");
      }).withMaxAttempts(2);
      bus.subscribeAsync("test.slow", Slow.class, (d, e) -> {
        seen(e.tag(), d);
        Thread.sleep(60_000); // interrupted at the timeout
      }).withMaxAttempts(2).withTimeout(Duration.ofMillis(500));
      bus.subscribeAsync("test.sql-block", StuckInSql.class, (d, e) -> {
        seen(e.tag(), d);
        // Interrupting the worker (a virtual thread) closes the socket: the query fails at once.
        uow.run(u -> Jdbc.queryFirst(u, "SELECT SLEEP(30)", rs -> rs.getInt(1)));
      }).withMaxAttempts(1).withTimeout(Duration.ofSeconds(1));
      bus.subscribeAsync("test.blocking", Blocking.class, (d, e) -> {
        seen(e.tag(), d);
        while (!gateOpen) {
          Thread.sleep(20); // throws when interrupted
        }
      }).withTimeout(Duration.ofMinutes(5));
    }
  }

  private static void seen(String tag, OutboxDelivery delivery) {
    SEEN.add(new Seen(tag, delivery, Thread.currentThread().getName()));
  }

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static EventBus bus;
  private static long systemUserId;
  private final OutboxRepository outbox = new OutboxRepository();

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(OutboxDispatcherMySqlTest.class, AuthSettings.defaults(),
        OutboxDispatcherMySqlTest::seed, List.of(new TestSubscribersModule()));
    uow = new UnitOfWorkFactory(stack.dataSource());
    bus = stack.services().get(EventBus.class);
    stack.host().startOutbox(SETTINGS);
    systemUserId = stack.services().get(SystemActor.class).userId();
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close(); // stops the outbox too
    }
  }

  private static void seed(Statement st) throws SQLException {
    st.execute("INSERT INTO companies (id, code, description, is_enabled) VALUES (1, 'ACME', 'Acme Inc.', TRUE)");
    st.execute("""
        INSERT INTO users (id, default_company_id, user_no, name, created_by, updated_by) VALUES
          (1, 1, 'U1', 'Admin', 1, 1)""");
    st.execute("INSERT INTO user_companies (user_id, company_id) VALUES (1, 1)");
  }

  @BeforeEach
  @SuppressWarnings("unused")
  void closeGate() {
    gateOpen = false;
  }

  // ---- helpers ----

  private static void publish(DomainEvent event) {
    uow.run(u -> bus.publish(u, event));
  }

  /** The rows whose event carries this tag. */
  private List<OutboxRow> rows(String tag) {
    return uow.executeReadOnly(u -> Jdbc.queryList(u,
        "SELECT id FROM outbox WHERE payload->>'$.tag' = ? ORDER BY id", rs -> rs.getLong(1), tag)
        .stream().map(id -> outbox.findById(u, id).orElseThrow()).toList());
  }

  private OutboxRow onlyRow(String tag) {
    List<OutboxRow> rows = rows(tag);
    assertEquals(1, rows.size(), "rows tagged " + tag);
    return rows.getFirst();
  }

  private static List<Seen> seen(String tag) {
    return SEEN.stream().filter(s -> s.tag().equals(tag)).toList();
  }

  private void awaitStatus(String tag, OutboxStatus status) {
    await(tag + " " + status, () -> {
      List<OutboxRow> rows = rows(tag);
      return rows.size() == 1 && rows.getFirst().status() == status;
    });
  }

  private static void await(String what, BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("Timed out waiting for: " + what);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
  }

  /** Lets a few polls go by, to show that something does NOT happen. */
  private static void settle() throws InterruptedException {
    Thread.sleep(600);
  }

  /** A row written by hand, as another instance (or an older version) would have left it. */
  private long insertRow(String key, String eventType, String payload, String status, int attempt,
      String lockedBy, String lockedUntilSql) {
    return uow.execute(u -> Jdbc.insert(u, "INSERT INTO outbox (subscription_key, event_type, payload, company_id,"
        + " status, attempt, available_at, locked_by, locked_until, created_at) VALUES (?, ?, ?, 1, ?, ?,"
        + " UTC_TIMESTAMP(), ?, " + lockedUntilSql + ", UTC_TIMESTAMP())",
        key, eventType, payload, status, attempt, lockedBy));
  }

  // ---- delivery ----

  @Test
  void aCommittedEventIsDeliveredOnceAsSystem() {
    publish(new Ping(1, "committed"));
    awaitStatus("committed", OutboxStatus.SUCCEEDED);

    List<Seen> seen = seen("committed");
    assertEquals(1, seen.size());
    OutboxDelivery d = seen.getFirst().delivery();
    OutboxRow row = onlyRow("committed");
    assertEquals(row.id(), d.messageId());
    assertEquals("test.record", d.subscriptionKey());
    assertEquals(1, d.attempt());
    assertEquals(8, d.maxAttempts()); // outbox.defaultMaxAttempts
    assertEquals(1, d.companyId());
    assertEquals(row.createdAt(), d.enqueuedAt());
    assertEquals(systemUserId, d.requestContext().userId());
    assertEquals(1, d.requestContext().companyId());
    assertTrue(d.requestContext().permissions().containsAll(stack.services().get(PermissionRegistry.class).names()));
    assertTrue(seen.getFirst().thread().startsWith("outbox-worker-"), seen.getFirst().thread());

    assertEquals(Ping.class.getName(), row.eventType());
    assertEquals(1, row.attempt());
    assertNull(row.lockedBy());
    assertNull(row.lastError());
    assertTrue(row.processedAt() != null);
  }

  @Test
  void aRolledBackEventLeavesNoRowAndIsNeverDelivered() throws Exception {
    try (UnitOfWork u = uow.begin()) {
      bus.publish(u, new Ping(1, "rolled-back"));
      assertEquals(1, Jdbc.queryFirst(u, "SELECT COUNT(*) FROM outbox WHERE payload->>'$.tag' = 'rolled-back'",
          rs -> rs.getInt(1)).orElseThrow()); // written in the publisher's transaction...
      u.rollback();
    }
    settle();
    assertEquals(List.of(), rows("rolled-back")); // ...and gone with it
    assertEquals(List.of(), seen("rolled-back"));
  }

  @Test
  void aFailingHandlerIsRetriedWithBackoffUntilItSucceeds() {
    publish(new Flaky(1, "flaky"));
    awaitStatus("flaky", OutboxStatus.SUCCEEDED);

    List<Seen> seen = seen("flaky");
    assertEquals(List.of(1, 2, 3), seen.stream().map(s -> s.delivery().attempt()).toList());
    OutboxRow row = onlyRow("flaky");
    assertEquals(3, row.attempt());
    assertTrue(row.lastError().startsWith("IllegalStateException: flaky attempt 2"), row.lastError());
    // Backoff of 1 s, then 2 s: at least 3 s between the commit and the success.
    assertTrue(!row.processedAt().isBefore(row.createdAt().plusSeconds(3)),
        row.createdAt() + " -> " + row.processedAt());
  }

  @Test
  void aHandlerThatAlwaysFailsEndsDead() throws Exception {
    publish(new Doomed(1, "doomed"));
    awaitStatus("doomed", OutboxStatus.DEAD);

    OutboxRow row = onlyRow("doomed");
    assertEquals(2, row.attempt()); // withMaxAttempts(2)
    assertTrue(row.lastError().startsWith("IllegalStateException: doomed\n"), row.lastError());
    assertTrue(row.lastError().contains("\tat "), "stack trace kept"); // for whoever investigates
    assertTrue(row.processedAt() != null);
    settle();
    assertEquals(List.of(1, 2), seen("doomed").stream().map(s -> s.delivery().attempt()).toList());
    assertTrue(seen("doomed").getLast().delivery().isLastAttempt());
  }

  @Test
  void aHandlerPastItsTimeoutIsInterruptedAndCountsAsFailed() {
    publish(new Slow(1, "slow"));
    awaitStatus("slow", OutboxStatus.DEAD);

    OutboxRow row = onlyRow("slow");
    assertEquals(2, row.attempt());
    assertTrue(row.lastError().startsWith("Timed out after PT0.5S (InterruptedException"), row.lastError());
    assertEquals(2, seen("slow").size());
  }

  @Test
  void aHandlerBlockedInAQueryIsStoppedAtItsTimeout() {
    long started = System.nanoTime();
    publish(new StuckInSql(1, "stuck"));
    awaitStatus("stuck", OutboxStatus.DEAD);

    long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
    assertTrue(seconds < 10, "took " + seconds + " s: the query wasn't stopped");
    OutboxRow row = onlyRow("stuck");
    assertEquals(1, row.attempt());
    assertTrue(row.lastError().startsWith("Timed out after PT1S"), row.lastError());
    assertEquals(1, seen("stuck").size());
  }

  // ---- rows left by others ----

  @Test
  void aRowWhoseLeaseExpiredIsDeliveredAgain() {
    long id = insertRow("test.record", Ping.class.getName(), "{\"companyId\": 1, \"tag\": \"orphan\"}",
        "PROCESSING", 1, "crashed-instance", "UTC_TIMESTAMP() - INTERVAL 1 MINUTE");
    awaitStatus("orphan", OutboxStatus.SUCCEEDED);

    OutboxRow row = onlyRow("orphan");
    assertEquals(id, row.id());
    assertEquals(2, row.attempt());
    assertTrue(row.lastError().startsWith("Lease of crashed-instance expired during attempt 1"), row.lastError());
    assertEquals(List.of(2), seen("orphan").stream().map(s -> s.delivery().attempt()).toList());
  }

  @Test
  void aLiveLeaseOfAnotherInstanceIsLeftAlone() throws Exception {
    long id = insertRow("test.record", Ping.class.getName(), "{\"companyId\": 1, \"tag\": \"leased\"}",
        "PROCESSING", 1, "other-instance", "UTC_TIMESTAMP() + INTERVAL 1 MINUTE");
    settle();
    OutboxRow row = onlyRow("leased");
    assertEquals(id, row.id());
    assertEquals(OutboxStatus.PROCESSING, row.status());
    assertEquals("other-instance", row.lockedBy());
    assertEquals(List.of(), seen("leased"));
  }

  @Test
  void aRowThatCannotBeReadBackIsDeadAtOnce() {
    insertRow("test.record", "com.example.RenamedEvent", "{\"companyId\": 1, \"tag\": \"unreadable\"}", "PENDING",
        0, null, "NULL");
    insertRow("test.record", Ping.class.getName(), "{\"companyId\": 1, \"tag\": \"bad-json\", \"extra\": 1}",
        "PENDING", 0, null, "NULL");
    awaitStatus("unreadable", OutboxStatus.DEAD);
    awaitStatus("bad-json", OutboxStatus.DEAD);

    assertTrue(onlyRow("unreadable").lastError().startsWith("UnreadableEventException: Event class "
        + "com.example.RenamedEvent not found"), onlyRow("unreadable").lastError());
    assertTrue(onlyRow("bad-json").lastError().startsWith("UnreadableEventException: Payload cannot be read"),
        onlyRow("bad-json").lastError());
    assertEquals(1, onlyRow("unreadable").attempt()); // no retries: it would fail the same way
    assertEquals(List.of(), seen("unreadable"));
    assertEquals(List.of(), seen("bad-json"));
  }

  // ---- several instances ----

  @Test
  void twoInstancesDeliverEachMessageExactlyOnce() {
    var second = new Outbox(bus);
    var services = stack.services();
    second.start(SETTINGS.withInstanceId("instance-b"), uow, services.get(SystemActor.class),
        services.get(PermissionRegistry.class));
    try {
      int n = 100;
      uow.run(u -> {
        for (int i = 0; i < n; i++) {
          bus.publish(u, new Ping(1, "shared-" + i));
        }
      });
      await("all " + n + " delivered", () -> uow.executeReadOnly(u -> Jdbc.queryFirst(u,
          "SELECT COUNT(*) FROM outbox WHERE payload->>'$.tag' LIKE 'shared-%' AND status = 'SUCCEEDED'",
          rs -> rs.getInt(1))).orElseThrow() == n);
      Set<String> threads = new HashSet<>();
      for (int i = 0; i < n; i++) {
        List<Seen> seen = seen("shared-" + i);
        assertEquals(1, seen.size(), "shared-" + i);
        assertEquals(1, seen.getFirst().delivery().attempt(), "shared-" + i);
        threads.add(seen.getFirst().thread());
      }
      System.out.println("[OutboxDispatcherMySqlTest] " + n + " messages delivered by threads " + threads);
    } finally {
      second.stop();
    }
    assertFalse(second.isRunning());
  }

  // ---- shutdown (last: it stops the class's dispatcher) ----

  @Test
  @Order(Integer.MAX_VALUE)
  void shutdownInterruptsRunningHandlersAndReleasesTheirMessages() {
    publish(new Blocking(1, "blocking"));
    await("running", () -> !seen("blocking").isEmpty());
    assertEquals(OutboxStatus.PROCESSING, onlyRow("blocking").status());

    Outbox running = stack.services().get(Outbox.class);
    Instant before = Instant.now();
    running.stop(); // grace 1 s, then interrupt
    assertFalse(running.isRunning());
    assertTrue(Duration.between(before, Instant.now()).compareTo(Duration.ofSeconds(1)) >= 0, "waited the grace");

    OutboxRow row = onlyRow("blocking");
    assertEquals(OutboxStatus.PENDING, row.status()); // another instance can deliver it at once
    assertEquals(0, row.attempt()); // the interrupted attempt does not count
    assertNull(row.lockedBy());
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.numbering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataAccessException;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.NumberRequest;

/**
 * DocumentNumbers as modules get it, on the V9 tables, with test-only document
 * types: both modes, rollbacks, scopes, companies, default series, explicit
 * series, bad data, and concurrency.
 */
class DocumentNumbersMySqlTest {

  private static final long ACME = 1;
  private static final long OTHER = 2;

  /** No parameters, gapless required: an invoice. */
  private static final String INVOICE = "test.invoice";
  /** Store + terminal, gaps allowed: a customer order. */
  private static final String ORDER = "test.order";
  /** Store + terminal, gapless: a till receipt. */
  private static final String RECEIPT = "test.receipt";
  /** No default series: a company must create one. */
  private static final String MANUAL = "test.manual";

  /** Declares the document types above, like a real module would. */
  private static final class TestNumberingModule implements ERPModule {

    @Override
    public String name() {
      return "test-numbering";
    }

    @Override
    public void registerEntities(EntityRegistry entityRegistry) {
      // none
    }

    @Override
    public void registerDocumentTypes(DocumentTypes types) {
      types.register(DocumentType.of(INVOICE, "Test invoices")
          .gaplessRequired()
          .withDefaultSeries("INV", "INV-{SEQ:6}", Mode.GAPLESS));
      types.register(DocumentType.of(ORDER, "Test customer orders")
          .withParameters("STORE_NO", "TERMINAL_NO")
          .withDefaultSeries("CO", "CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}", Mode.GAPS_ALLOWED));
      types.register(DocumentType.of(RECEIPT, "Test receipts")
          .withParameters("STORE_NO", "TERMINAL_NO")
          .withDefaultSeries("R", "R{STORE_NO:2}{TERMINAL_NO:2}-{SEQ:5}", Mode.GAPLESS));
      types.register(DocumentType.of(MANUAL, "Test documents with no default series")
          .withParameters("STORE_NO"));
    }
  }

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static DocumentNumbers numbers;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(DocumentNumbersMySqlTest.class, AuthSettings.defaults(),
        st -> st.execute("INSERT INTO companies (id, code, is_enabled) VALUES (1, 'ACME', TRUE), (2, 'OTHER', TRUE)"),
        List.of(new TestNumberingModule()));
    uow = new UnitOfWorkFactory(stack.dataSource());
    numbers = stack.services().get(DocumentNumbers.class);
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  @BeforeEach
  @SuppressWarnings("unused")
  void noSeries() {
    uow.run(u -> {
      Jdbc.update(u, "DELETE FROM number_series_counters");
      Jdbc.update(u, "DELETE FROM number_series");
    });
  }

  // ---- helpers ----

  private static NumberRequest order(long company, String store, String terminal) {
    return NumberRequest.of(company, ORDER).param("STORE_NO", store).param("TERMINAL_NO", terminal);
  }

  private static NumberRequest receipt(String store, String terminal) {
    return NumberRequest.of(ACME, RECEIPT).param("STORE_NO", store).param("TERMINAL_NO", terminal);
  }

  /** A number in a transaction of its own, committed. */
  private static String next(NumberRequest request) {
    return uow.execute(u -> numbers.next(u, request));
  }

  /** A number taken in a transaction that then rolls back. */
  private static String nextThenRollBack(NumberRequest request) {
    try (UnitOfWork u = uow.begin()) {
      String number = numbers.next(u, request);
      u.rollback();
      return number;
    }
  }

  private static void insertSeries(long company, String type, String code, String pattern, Mode mode,
      boolean isDefault, boolean enabled, long startValue) {
    uow.run(u -> Jdbc.update(u, """
        INSERT INTO number_series (company_id, document_type, code, pattern, mode, is_default, is_enabled,
          start_value)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", company, type, code, pattern, mode, isDefault, enabled, startValue));
  }

  private static String conflictCode(NumberRequest request) {
    return assertThrows(ConflictException.class, () -> next(request)).code();
  }

  // ---- gapless ----

  @Test
  void gaplessNumbersAreConsecutive() {
    assertEquals("INV-000001", next(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-000002", next(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-000003", next(NumberRequest.of(ACME, INVOICE)));
  }

  @Test
  void gaplessRollbackGivesTheNumberBack() {
    assertEquals("INV-000001", next(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-000002", nextThenRollBack(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-000002", next(NumberRequest.of(ACME, INVOICE)));
  }

  @Test
  void gaplessRollbackOfTheVeryFirstNumber() {
    // The counter was created in a transaction of its own: it stays, at start - 1.
    assertEquals("INV-000001", nextThenRollBack(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-000001", next(NumberRequest.of(ACME, INVOICE)));
  }

  @Test
  void severalNumbersInOneTransaction() {
    List<String> taken = uow.execute(u -> List.of(
        numbers.next(u, NumberRequest.of(ACME, INVOICE)),
        numbers.next(u, NumberRequest.of(ACME, INVOICE))));
    assertEquals(List.of("INV-000001", "INV-000002"), taken);
  }

  // ---- gaps allowed ----

  @Test
  void gapsAllowedRollbackLosesTheNumber() {
    assertEquals("CO-01203000001", next(order(ACME, "12", "3")));
    assertEquals("CO-01203000002", nextThenRollBack(order(ACME, "12", "3")));
    assertEquals("CO-01203000003", next(order(ACME, "12", "3")));
  }

  @Test
  void gapsAllowedNumberIsCommittedBeforeTheCallerCommits() {
    try (UnitOfWork caller = uow.begin()) {
      assertEquals("CO-01203000001", numbers.next(caller, order(ACME, "12", "3")));
      long committed = uow.executeReadOnly(u -> Jdbc.queryFirst(u,
          "SELECT last_number FROM number_series_counters", rs -> rs.getLong(1)).orElseThrow());
      assertEquals(1, committed);
    }
  }

  // ---- scopes and companies ----

  @Test
  void eachTerminalHasItsOwnCounter() {
    assertEquals("CO-01203000001", next(order(ACME, "12", "3")));
    assertEquals("CO-01203000002", next(order(ACME, "12", "3")));
    assertEquals("CO-01204000001", next(order(ACME, "12", "4")));
    assertEquals("CO-00103000001", next(order(ACME, "1", "3")));
    // 12 and 012 are the same store, so the same counter.
    assertEquals("CO-01203000003", next(order(ACME, "012", "03")));
    List<String> scopes = uow.executeReadOnly(u -> Jdbc.queryList(u,
        "SELECT scope FROM number_series_counters ORDER BY scope", rs -> rs.getString(1)));
    assertEquals(List.of("STORE_NO=001;TERMINAL_NO=03", "STORE_NO=012;TERMINAL_NO=03",
        "STORE_NO=012;TERMINAL_NO=04"), scopes);
  }

  @Test
  void eachCompanyHasItsOwnSeriesAndCounters() {
    assertEquals("INV-000001", next(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-000002", next(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-000001", next(NumberRequest.of(OTHER, INVOICE)));
    long series = uow.executeReadOnly(u -> Jdbc.queryFirst(u,
        "SELECT COUNT(*) FROM number_series WHERE document_type = ?", rs -> rs.getLong(1), INVOICE).orElseThrow());
    assertEquals(2, series);
  }

  // ---- default series ----

  @Test
  void defaultSeriesIsCreatedOnFirstUse() {
    next(NumberRequest.of(ACME, INVOICE));
    List<String> row = uow.executeReadOnly(u -> Jdbc.queryFirst(u, """
        SELECT code, description, pattern, mode, is_default, is_enabled, start_value, created_by
          FROM number_series WHERE company_id = ? AND document_type = ?""",
        rs -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), String.valueOf(rs.getObject(8))),
        ACME, INVOICE).orElseThrow());
    assertEquals(List.of("INV", "Test invoices", "INV-{SEQ:6}", "GAPLESS", "1", "1", "1", "null"), row);
  }

  @Test
  void aCompanysOwnDefaultSeriesWins() {
    insertSeries(ACME, INVOICE, "FAC", "FAC{SEQ:4}", Mode.GAPLESS, true, true, 1);
    assertEquals("FAC0001", next(NumberRequest.of(ACME, INVOICE)));
  }

  @Test
  void aDisabledDefaultIsNotReplaced() {
    // The company turned its default off: numbering stops, it doesn't come back as INV.
    insertSeries(OTHER, INVOICE, "OLD", "OLD{SEQ:4}", Mode.GAPLESS, true, false, 1);
    assertEquals("NUMBER_SERIES_NOT_FOUND", conflictCode(NumberRequest.of(OTHER, INVOICE)));
  }

  @Test
  void aNonDefaultSeriesDoesNotStopTheDefault() {
    insertSeries(ACME, ORDER, "WEB", "WEB-{SEQ:5}", Mode.GAPS_ALLOWED, false, true, 1);
    assertEquals("CO-00101000001", next(order(ACME, "1", "1")));
  }

  @Test
  void noDefaultWhenItsCodeIsTaken() {
    // CO exists but is not the default: it is never promoted behind the company's back.
    insertSeries(ACME, ORDER, "CO", "CO{SEQ:5}", Mode.GAPS_ALLOWED, false, true, 1);
    assertEquals("NUMBER_SERIES_NOT_FOUND", conflictCode(order(ACME, "1", "1")));
  }

  @Test
  void typeWithoutDefaultNeedsASeries() {
    assertEquals("NUMBER_SERIES_NOT_FOUND", conflictCode(NumberRequest.of(ACME, MANUAL).param("STORE_NO", "1")));
    insertSeries(ACME, MANUAL, "M", "M{STORE_NO:2}-{SEQ:3}", Mode.GAPLESS, true, true, 1);
    assertEquals("M01-001", next(NumberRequest.of(ACME, MANUAL).param("STORE_NO", "1")));
  }

  @Test
  void concurrentFirstUsesCreateOneDefault() throws Exception {
    List<String> taken = runConcurrently(5, 10, () -> next(NumberRequest.of(ACME, INVOICE)));
    assertEquals(IntStream.rangeClosed(1, 10).mapToObj(i -> String.format("INV-%06d", i)).toList(),
        taken.stream().sorted().toList());
    long series = uow.executeReadOnly(u -> Jdbc.queryFirst(u, "SELECT COUNT(*) FROM number_series",
        rs -> rs.getLong(1)).orElseThrow());
    assertEquals(1, series);
  }

  // ---- explicit series ----

  @Test
  void explicitSeries() {
    insertSeries(ACME, ORDER, "WEB", "WEB-{SEQ:5}", Mode.GAPS_ALLOWED, false, true, 1);
    assertEquals("WEB-00001", next(order(ACME, "12", "3").series("WEB")));
    assertEquals("CO-01203000001", next(order(ACME, "12", "3")));
    assertEquals("WEB-00002", next(NumberRequest.of(ACME, ORDER).series("WEB")));
  }

  @Test
  void unknownOrDisabledSeries() {
    assertEquals("NUMBER_SERIES_NOT_FOUND", conflictCode(order(ACME, "1", "1").series("NOPE")));
    insertSeries(ACME, ORDER, "OFF", "OFF-{SEQ:5}", Mode.GAPS_ALLOWED, false, false, 1);
    assertEquals("NUMBER_SERIES_NOT_FOUND", conflictCode(order(ACME, "1", "1").series("OFF")));
  }

  @Test
  void startValue() {
    insertSeries(ACME, INVOICE, "INV", "INV-{SEQ:6}", Mode.GAPLESS, true, true, 5001);
    assertEquals("INV-005001", next(NumberRequest.of(ACME, INVOICE)));
    assertEquals("INV-005002", next(NumberRequest.of(ACME, INVOICE)));
  }

  // ---- errors ----

  @Test
  void badParametersAreValidationErrors() {
    ValidationFailedException e = assertThrows(ValidationFailedException.class,
        () -> next(NumberRequest.of(ACME, ORDER).param("STORE_NO", "1234")));
    assertEquals(List.of("params.STORE_NO:TOO_LONG", "params.TERMINAL_NO:REQUIRED"),
        e.fieldErrors().stream().map(f -> f.field() + ":" + f.code()).sorted().toList());
    // Nothing was created for a refused request but the default series.
    long counters = uow.executeReadOnly(u -> Jdbc.queryFirst(u, "SELECT COUNT(*) FROM number_series_counters",
        rs -> rs.getLong(1)).orElseThrow());
    assertEquals(0, counters);
    assertTrue(e.fieldErrors().stream().map(FieldError::message).allMatch(m -> !m.isBlank()));
  }

  @Test
  void unregisteredTypeIsAWiringBug() {
    assertThrows(IllegalStateException.class, () -> next(NumberRequest.of(ACME, "test.nope")));
  }

  @Test
  void storedSeriesThatNoLongerFitsItsType() {
    // Written by hand: the admin API will refuse both.
    insertSeries(ACME, INVOICE, "GAPS", "G{SEQ:4}", Mode.GAPS_ALLOWED, true, true, 1);
    assertThrows(IllegalStateException.class, () -> next(NumberRequest.of(ACME, INVOICE)));
    insertSeries(ACME, MANUAL, "BAD", "B{TERMINAL_NO:2}{SEQ:4}", Mode.GAPLESS, true, true, 1);
    String message = assertThrows(IllegalStateException.class,
        () -> next(NumberRequest.of(ACME, MANUAL).param("TERMINAL_NO", "1"))).getMessage();
    assertTrue(message.contains("unknown parameter TERMINAL_NO"), message);
  }

  // ---- concurrency ----

  @Test
  void concurrentGaplessNumbersHaveNoGapsAndNoDuplicates() throws Exception {
    List<String> taken = runConcurrently(5, 40, () -> next(NumberRequest.of(ACME, INVOICE)));
    assertEquals(IntStream.rangeClosed(1, 40).mapToObj(i -> String.format("INV-%06d", i)).toList(),
        taken.stream().sorted().toList());
  }

  @Test
  void concurrentGaplessNumbersWithRollbacksHaveNoGaps() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<String> taken = runConcurrently(5, 45, () -> {
      // Every third caller rolls back: its number goes to the next one.
      if (calls.incrementAndGet() % 3 == 0) {
        nextThenRollBack(NumberRequest.of(ACME, INVOICE));
        return null;
      }
      return next(NumberRequest.of(ACME, INVOICE));
    });
    List<String> committed = taken.stream().filter(n -> n != null).sorted().toList();
    assertEquals(IntStream.rangeClosed(1, 30).mapToObj(i -> String.format("INV-%06d", i)).toList(), committed);
  }

  @Test
  void concurrentNumbersWithGapsAreUnique() throws Exception {
    List<String> taken = runConcurrently(5, 40, () -> next(order(ACME, "12", "3")));
    Set<String> unique = new TreeSet<>(taken);
    assertEquals(40, unique.size());
  }

  /**
   * Every connection of the main pool held by a caller that needs a number:
   * the counter's own transactions use the autonomous pool, so nobody waits
   * on a connection that only another waiting caller could give back.
   */
  @Test
  void moreCallersThanPoolConnectionsDoNotDeadlock() throws Exception {
    int callers = stack.dataSource().getMaximumPoolSize() * 2;
    long started = System.nanoTime();
    List<String> taken = runConcurrently(callers, callers * 3, () -> uow.execute(u -> {
      String number = numbers.next(u, order(ACME, "12", "3"));
      java.util.concurrent.locks.LockSupport.parkNanos(50_000_000); // hold the connection, so all callers do at once
      return number;
    }));
    long seconds = java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds();
    assertEquals(callers * 3, new TreeSet<>(taken).size());
    assertTrue(seconds < 20, "took " + seconds + " s: callers waited on the pool they were holding");
  }

  @Test
  void openGaplessTransactionsOnDifferentTerminalsDoNotWait() {
    next(receipt("1", "1"));
    next(receipt("1", "2"));
    try (UnitOfWork first = uow.begin(); UnitOfWork second = uow.begin()) {
      shortLockWait(second);
      assertEquals("R0101-00002", numbers.next(first, receipt("1", "1")));
      // first holds terminal 1's counter; terminal 2's (existing) and terminal 3's (new) are free.
      assertEquals("R0102-00002", numbers.next(second, receipt("1", "2")));
      assertEquals("R0103-00001", numbers.next(second, receipt("1", "3")));
      first.commit();
      second.commit();
    }
  }

  @Test
  void anOpenGaplessTransactionHoldsItsCounter() {
    next(receipt("1", "1"));
    try (UnitOfWork first = uow.begin()) {
      assertEquals("R0101-00002", numbers.next(first, receipt("1", "1")));
      try (UnitOfWork second = uow.begin()) {
        shortLockWait(second);
        assertThrows(DataAccessException.class, () -> numbers.next(second, receipt("1", "1")));
      }
      first.rollback();
    }
    assertEquals("R0101-00002", next(receipt("1", "1")));
  }

  /** Fails a blocked statement after 1 second instead of 50. */
  private static void shortLockWait(UnitOfWork u) {
    Jdbc.update(u, "SET SESSION innodb_lock_wait_timeout = 1");
  }

  /**
   * Runs {@code tasks} calls on {@code threads} threads, all started at once.
   * The calls' own UnitOfWorks come from the main pool; the second connection
   * a number may need comes from the autonomous one.
   */
  private static <T> List<T> runConcurrently(int threads, int tasks, Callable<T> task) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<T>> calls = new ArrayList<>();
      for (int i = 0; i < tasks; i++) {
        calls.add(task);
      }
      List<T> results = new ArrayList<>();
      for (Future<T> f : pool.invokeAll(calls)) {
        results.add(f.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}

package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Public API of the general ledger, published in the ServiceRegistry
 * (docs/ARCHITECTURE.md §22.7). Other modules post their journal entries
 * through it, in the transaction that saves their document:
 *
 * <pre>
 * JournalEntryRef je = generalLedger.post(uow, rc, JournalEntryRequest.of(invoiceDate, "Invoice " + invoiceNo)
 *     .source("sales.invoice", invoiceId, invoiceNo)
 *     .line(LineRequest.debit("1200", total).dimension("STORE", storeNo))
 *     .line(LineRequest.credit("4000", subtotal).dimension("STORE", storeNo))
 *     .line(LineRequest.credit("2310", gst)));
 * </pre>
 *
 * <ul>
 * <li>Accounts and dimension values by code, as people know them.</li>
 * <li>Amounts in the company's base currency, already rounded to its minor
 * unit (2 decimals for CAD), each line one side only, debits = credits.</li>
 * <li>The posting date must fall in an OPEN period.</li>
 * <li>Modules may post to control accounts ({@code manualPosting = false});
 * only source {@link #MANUAL} entries may not.</li>
 * <li>No permission is checked: the calling module checked its own. The entry
 * is recorded as posted by {@code rc.userId()}.</li>
 * </ul>
 */
public interface GeneralLedgerApi {

  /** The source type of entries typed in by a person (POST /gl/journal-entries). */
  String MANUAL = "MANUAL";

  /**
   * Validates and posts the entry in {@code uow}: it commits or rolls back
   * with the caller's document. Takes a gapless number (gl.journal-entry) and
   * publishes JournalEntryPosted.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException
   *         every problem with the request, as {@code lines[i].field}
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException
   *         PERIOD_NOT_FOUND / PERIOD_CLOSED for the posting date
   */
  JournalEntryRef post(UnitOfWork uow, RequestContext rc, JournalEntryRequest request);

  // ---- request ----

  /**
   * @param sourceType MANUAL, or the posting module's document type, e.g.
   *                   "sales.invoice"
   * @param sourceId   the source document's id, null for MANUAL
   * @param sourceNo   the source document's number, shown in reports
   */
  record JournalEntryRequest(LocalDate postingDate, String description, String sourceType, Long sourceId,
      String sourceNo, List<LineRequest> lines) {

    public JournalEntryRequest {
      lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    public static JournalEntryRequest of(LocalDate postingDate, String description) {
      return new JournalEntryRequest(postingDate, description, null, null, null, List.of());
    }

    public JournalEntryRequest source(String type, Long id, String no) {
      return new JournalEntryRequest(postingDate, description, type, id, no, lines);
    }

    public JournalEntryRequest line(LineRequest line) {
      List<LineRequest> more = new ArrayList<>(lines);
      more.add(Objects.requireNonNull(line, "line"));
      return new JournalEntryRequest(postingDate, description, sourceType, sourceId, sourceNo, more);
    }
  }

  /**
   * One line: an account and exactly one of debit / credit above zero.
   *
   * @param debit      null or zero when the line is a credit
   * @param credit     null or zero when the line is a debit
   * @param dimensions dimension code -> value code; may be empty
   */
  record LineRequest(String accountNo, BigDecimal debit, BigDecimal credit, String description,
      Map<String, String> dimensions) {

    public LineRequest {
      dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(
          Objects.requireNonNull(dimensions, "dimensions")));
    }

    public static LineRequest debit(String accountNo, BigDecimal amount) {
      return new LineRequest(accountNo, amount, null, null, Map.of());
    }

    public static LineRequest credit(String accountNo, BigDecimal amount) {
      return new LineRequest(accountNo, null, amount, null, Map.of());
    }

    public LineRequest description(String text) {
      return new LineRequest(accountNo, debit, credit, text, dimensions);
    }

    public LineRequest dimension(String dimensionCode, String valueCode) {
      Map<String, String> more = new LinkedHashMap<>(dimensions);
      more.put(dimensionCode, valueCode);
      return new LineRequest(accountNo, debit, credit, description, more);
    }
  }

  // ---- result ----

  /** The posted entry. */
  record JournalEntryRef(long id, String entryNo) {
  }
}

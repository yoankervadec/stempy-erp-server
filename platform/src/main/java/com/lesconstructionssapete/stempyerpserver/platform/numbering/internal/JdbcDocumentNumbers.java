package com.lesconstructionssapete.stempyerpserver.platform.numbering.internal;

import java.util.Objects;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.NumberPattern;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.NumberRequest;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.internal.SeriesRepository.SeriesRow;

/**
 * DocumentNumbers on the V9 tables (docs/ARCHITECTURE.md §21.5).
 *
 * The connection pool runs REPEATABLE READ, which shapes every step:
 * <ol>
 * <li>The series is read without locks in the caller's transaction. A company
 * with no default series for the type (enabled or not) gets the type's default
 * in a short transaction of its own, read back there with a current read (the
 * caller's snapshot may predate it). A disabled default is never replaced.</li>
 * <li>A missing counter is created with INSERT IGNORE in a short transaction
 * of its own (last_number = start_value - 1). Creating it in the caller's
 * transaction would hold its insert locks until the caller commits, and an
 * UPDATE that finds no row there would leave a gap lock blocking the insert.</li>
 * <li>The counter is incremented with a plain UPDATE, then read back: in the
 * caller's transaction for GAPLESS, in one of its own for GAPS_ALLOWED.</li>
 * </ol>
 */
public final class JdbcDocumentNumbers implements DocumentNumbers {

  public static final String SERIES_NOT_FOUND = "NUMBER_SERIES_NOT_FOUND";

  private final DocumentTypes types;
  private final UnitOfWorkFactory uowFactory;
  private final SeriesRepository series;
  private final CounterRepository counters;

  public JdbcDocumentNumbers(DocumentTypes types, UnitOfWorkFactory uowFactory, SeriesRepository series,
      CounterRepository counters) {
    this.types = Objects.requireNonNull(types, "types");
    this.uowFactory = Objects.requireNonNull(uowFactory, "uowFactory");
    this.series = Objects.requireNonNull(series, "series");
    this.counters = Objects.requireNonNull(counters, "counters");
  }

  @Override
  public String next(UnitOfWork uow, NumberRequest request) {
    Objects.requireNonNull(uow, "uow");
    Objects.requireNonNull(request, "request");
    DocumentType type = types.require(request.documentType());
    SeriesRow row = resolveSeries(uow, type, request);
    NumberPattern.Applied applied = pattern(type, row).apply(request.params());
    long value = row.mode() == Mode.GAPLESS
        ? takeGapless(uow, row, applied.scope())
        : takeWithGaps(row, applied.scope());
    return applied.format(value);
  }

  private SeriesRow resolveSeries(UnitOfWork uow, DocumentType type, NumberRequest request) {
    long companyId = request.companyId();
    if (request.seriesCode() != null) {
      return series.findEnabled(uow, companyId, type.key(), request.seriesCode())
          .orElseThrow(() -> new ConflictException(SERIES_NOT_FOUND, "No enabled number series "
              + request.seriesCode() + " for " + type.key()));
    }
    return series.findDefault(uow, companyId, type.key(), false)
        .or(() -> type.defaultSeries() == null ? Optional.empty()
            : uowFactory.execute(u -> {
              if (!series.existsDefault(u, companyId, type.key())) {
                series.insertDefaultIgnoringDuplicates(u, companyId, type);
              }
              return series.findDefault(u, companyId, type.key(), true);
            }))
        .orElseThrow(() -> new ConflictException(SERIES_NOT_FOUND, "No enabled default number series for "
            + type.key()));
  }

  /** The stored pattern, checked against what the type declares today. */
  private static NumberPattern pattern(DocumentType type, SeriesRow row) {
    if (type.isGaplessRequired() && row.mode() != Mode.GAPLESS) {
      throw new IllegalStateException("Number series " + row.id() + " (" + row.code() + ") of " + type.key()
          + " is " + row.mode() + " but the type requires GAPLESS");
    }
    try {
      return NumberPattern.parse(row.pattern(), type.parameters());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Number series " + row.id() + " (" + row.code() + ") of " + type.key()
          + " has a pattern its type no longer accepts: " + e.getMessage(), e);
    }
  }

  private long takeGapless(UnitOfWork uow, SeriesRow row, String scope) {
    if (!counters.exists(uow, row.id(), scope)) {
      createCounter(row, scope);
    }
    if (counters.increment(uow, row.id(), scope) != 1) {
      throw new IllegalStateException("counter " + row.id() + "/" + scope + " not found after creating it");
    }
    return counters.lastNumber(uow, row.id(), scope);
  }

  private long takeWithGaps(SeriesRow row, String scope) {
    Long value = incrementAlone(row, scope);
    if (value == null) {
      createCounter(row, scope);
      value = incrementAlone(row, scope);
    }
    if (value == null) {
      throw new IllegalStateException("counter " + row.id() + "/" + scope + " not found after creating it");
    }
    return value;
  }

  /** Increments in a transaction of its own; null when the counter doesn't exist yet. */
  private Long incrementAlone(SeriesRow row, String scope) {
    return uowFactory.execute(u -> counters.increment(u, row.id(), scope) == 1
        ? counters.lastNumber(u, row.id(), scope)
        : null);
  }

  private void createCounter(SeriesRow row, String scope) {
    uowFactory.run(u -> counters.insertIgnoringDuplicate(u, row.id(), scope, row.startValue() - 1));
  }
}

package com.lesconstructionssapete.stempyerpserver.platform.numbering;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * Hands out document numbers (docs/ARCHITECTURE.md §21). Published in the
 * ServiceRegistry by ModuleHost.
 *
 * <pre>
 * // In the operation that inserts the document, just before the insert:
 * String no = documentNumbers.next(uow, NumberRequest.of(companyId, "sales.invoice"));
 * </pre>
 *
 * <ul>
 * <li>GAPLESS series: the number is taken in {@code uow}. A rollback gives it
 * back; the counter (only that series and scope) stays locked until
 * {@code uow} ends, so call this as late as possible.</li>
 * <li>GAPS_ALLOWED series: the number is taken and committed in a transaction
 * of its own; {@code uow} is not used. A rollback of {@code uow} loses it.</li>
 * <li>A company with no series at all for the type gets the type's default
 * series, committed in a short transaction of its own.</li>
 * <li>A counter's first use creates it in a short transaction of its own, so
 * a GAPLESS call may briefly need a second connection.</li>
 * </ul>
 *
 * Never delete a document that has a GAPLESS number: cancel or reverse it.
 */
public interface DocumentNumbers {

  /**
   * @return the formatted number, e.g. {@code CO-01203012346}
   * @throws IllegalStateException for a document type no module registered,
   *                               or a series whose stored pattern no longer
   *                               fits its type (a data problem)
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException
   *                               {@code NUMBER_SERIES_NOT_FOUND}: no enabled
   *                               series with that code, or no default
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException
   *                               a missing, too long or badly written
   *                               parameter ({@code params.NAME})
   */
  String next(UnitOfWork uow, NumberRequest request);
}

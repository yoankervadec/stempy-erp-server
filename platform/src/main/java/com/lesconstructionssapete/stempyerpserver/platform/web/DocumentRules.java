package com.lesconstructionssapete.stempyerpserver.platform.web;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;

/**
 * A document's business rules (docs/ARCHITECTURE.md §23.8), on top of what
 * any record's RecordRules say (locked, canUpdate, defaults). Registered with
 * the Document; run on every save and every POST .../calculate, in that
 * request's transaction, on the whole document as it would be saved (Draft).
 * Implemented by the module's orchestrator (*Service), reading through its
 * operations.
 */
public interface DocumentRules extends RecordRules {

  /**
   * Fills the values the server computes (line amounts, taxes, totals) with
   * Draft.set(...), and adds non-blocking warnings with Draft.warn(...).
   * Runs before the checks, so they see the computed values.
   */
  default void calculate(UnitOfWork uow, RequestContext rc, Draft draft) {
  }

  /**
   * Rules the field declarations can't express ("at least one line", "the
   * payments don't exceed the total"), reported on paths like
   * "lines[2].quantity" or "payments".
   */
  default void validate(UnitOfWork uow, RequestContext rc, Draft draft, Validation v) {
  }
}

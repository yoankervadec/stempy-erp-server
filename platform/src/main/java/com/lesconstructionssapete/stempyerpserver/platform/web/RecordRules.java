package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Map;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;

/**
 * What one record's state allows (docs/ARCHITECTURE.md §23.9), for an entity
 * whose rules go beyond its field declarations and permissions: a posted
 * entry is locked, an account with postings keeps its type. Registered with
 * EntityEndpoints.rules(entity, rules); GET answers carry the result as
 * "_meta", and GET {path}/new uses defaults().
 *
 * It mostly describes: the writes still enforce their rules themselves, except
 * that the generic create and update (EntityEndpoints) enforce locked,
 * canUpdate, canDelete and validateWrite for an entity registered there. Every
 * method runs in the read's transaction and receives the row as the caller
 * sees it (API field names, readable fields only). Implemented by a module's
 * orchestrator (*Service), which reaches the database through its operations.
 */
public interface RecordRules {

  /** Every field: the whole record is read-only in this state. */
  String ALL = "*";

  /** The fields this record's state freezes (ALL for every one); empty: none beyond the field declarations. */
  default Set<String> locked(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
    return Set.of();
  }

  /** False when this record can't be changed at all in its state, permission or not. */
  default boolean canUpdate(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
    return true;
  }

  /** False when this record can't be deleted in its state, permission or not. */
  default boolean canDelete(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
    return true;
  }

  /**
   * Rules the field declarations can't express ("one rate per currency and
   * date", "not the company's own currency"), checked by the generic create
   * and update in their transaction, before the row is written: every error
   * added to {@code v} is answered as 422 VALIDATION_FAILED and nothing is
   * written. Runs after the fields passed their own checks.
   *
   * @param record the record as it would be saved, by API field name: for a
   *               create, the fields the body set (an id is absent); for an
   *               update, the current row as the caller sees it with the
   *               patch applied (its id included)
   */
  default void validateWrite(UnitOfWork uow, RequestContext rc, Map<String, Object> record, Validation v) {
  }

  /**
   * Values a new record starts with beyond the fields' declared defaults
   * (today's date, the company's currency), by API field name.
   */
  default Map<String, Object> defaults(UnitOfWork uow, RequestContext rc) {
    return Map.of();
  }
}

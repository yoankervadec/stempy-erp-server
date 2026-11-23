package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Checks a schedule's parameters (or a manual run's override) for one job
 * type. Called when a schedule is saved through the API and again before each
 * run, since rows can be edited with SQL.
 */
@FunctionalInterface
public interface ParameterValidator {

  /** Accepts anything. */
  ParameterValidator ANY = parameters -> {
  };

  /**
   * @param parameters never null: an empty object when the schedule has none
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException
   *         naming the faulty fields (e.g. "parameters.days")
   */
  void validate(JsonNode parameters);
}

package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.util.Optional;

/**
 * Filter operators a client may use. Which ones are legal for a field depends
 * on its FieldType.
 */
public enum Operator {

  EQUALS("equals"),
  NOT_EQUALS("notEquals"),
  GREATER_THAN("greaterThan"),
  GREATER_THAN_OR_EQUAL("greaterThanOrEqual"),
  LESS_THAN("lessThan"),
  LESS_THAN_OR_EQUAL("lessThanOrEqual"),
  IN("in"),
  CONTAINS("contains"),
  STARTS_WITH("startsWith"),
  IS_NULL("isNull");

  private final String wire;

  Operator(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  public static Optional<Operator> fromWire(String wire) {
    for (Operator op : values()) {
      if (op.wire.equals(wire)) {
        return Optional.of(op);
      }
    }
    return Optional.empty();
  }
}

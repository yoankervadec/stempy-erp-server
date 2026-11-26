package com.lesconstructionssapete.stempyerpserver.platform.entity;

/**
 * How a client should show and edit a field, when its type alone doesn't say
 * (docs/ARCHITECTURE.md §23.4). Declared with Field.money(), percent(),
 * multiline().
 */
public enum Format {
  /** A decimal amount, in the row's currency (Field.currencyField()) or the company's. */
  MONEY,
  /** A decimal shown as a percentage: the stored value is the percentage itself (5.25 is 5.25 %). */
  PERCENT,
  /** A string edited in a text area. */
  MULTILINE
}

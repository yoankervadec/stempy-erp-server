package com.lesconstructionssapete.stempyerpserver.modules.gl;

/**
 * What an account says about one dimension (gl_account_dimension_rules.rule_type).
 * No rule: the dimension is optional on that account's lines.
 */
public enum DimensionRule {
  /** Every line on the account must carry the dimension. */
  REQUIRED,
  /** No line on the account may carry the dimension. */
  NOT_ALLOWED
}

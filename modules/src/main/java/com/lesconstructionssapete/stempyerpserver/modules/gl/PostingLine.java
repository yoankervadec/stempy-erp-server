package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;

/**
 * A line ready to insert: account and dimension set resolved, amounts checked.
 * Exactly one of debit / credit is above zero; the other is zero.
 *
 * @param dimensionSetId null: no dimensions
 */
public record PostingLine(long accountId, BigDecimal debit, BigDecimal credit, String description,
    Long dimensionSetId) {
}

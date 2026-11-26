package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.math.BigDecimal;

/** A gl_journal_lines row, with its account's number and name. */
public record StoredLine(int lineNo, long accountId, String accountNo, String accountName, BigDecimal debit,
    BigDecimal credit, String description, Long dimensionSetId) {
}

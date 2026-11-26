package com.lesconstructionssapete.stempyerpserver.modules.gl.events;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;

/**
 * A journal entry was posted (published in the posting transaction). For a
 * reversal, {@code reversalOfId} names the entry it reverses.
 *
 * @param sourceType MANUAL, or the posting module's document type
 * @param total      the sum of the debits, in the company's base currency
 */
public record JournalEntryPosted(long companyId, long entryId, String entryNo, LocalDate postingDate,
    String sourceType, Long sourceId, Long reversalOfId, BigDecimal total) implements DomainEvent {
}

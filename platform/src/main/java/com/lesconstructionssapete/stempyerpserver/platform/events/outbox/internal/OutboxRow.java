package com.lesconstructionssapete.stempyerpserver.platform.events.outbox.internal;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxStatus;

/** One row of the outbox table. */
public record OutboxRow(
    long id,
    String subscriptionKey,
    String eventType,
    String payload,
    long companyId,
    OutboxStatus status,
    int attempt,
    Instant availableAt,
    String lockedBy,
    Instant lockedUntil,
    String lastError,
    Instant createdAt,
    Instant processedAt) {
}

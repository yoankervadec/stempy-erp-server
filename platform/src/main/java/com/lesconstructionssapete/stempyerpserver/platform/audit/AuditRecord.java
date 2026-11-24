package com.lesconstructionssapete.stempyerpserver.platform.audit;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An audit_log row as read back.
 *
 * @param changes {"field": {"old": ..., "new": ...}}, values as JSON (decimals
 *                and dates as strings); empty when the action carries none
 */
public record AuditRecord(long id, Long companyId, String entity, long entityId, String action, Long userId,
    String requestId, Instant occurredAt, ObjectNode changes) {
}

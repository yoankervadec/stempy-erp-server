package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;

/**
 * A run this instance claimed: its run row is RUNNING and the schedule is
 * leased to us. {@code schedule} is the row as locked at claim time.
 *
 * @param requestedBy for MANUAL runs only
 * @param parameters  JSON text (the manual override if any, else the
 *                    schedule's); null when none
 */
record Claim(long runId, ScheduleRow schedule, TriggerType trigger, int attempt, Instant scheduledFor,
    Long requestedBy, String parameters) {
}

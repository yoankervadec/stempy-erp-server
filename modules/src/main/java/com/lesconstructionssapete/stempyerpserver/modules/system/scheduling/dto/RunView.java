package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.ScheduleScope;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;

/**
 * One run as the API returns it.
 *
 * @param requestedBy the user who started a MANUAL run (it acted as them)
 * @param errorDetail the stack trace; only shown to holders of scheduler.system.manage
 */
public record RunView(
    long id,
    long scheduleId,
    ScheduleScope scope,
    String jobKey,
    TriggerType trigger,
    Long requestedBy,
    JsonNode parametersOverride,
    RunStatus status,
    int attempt,
    Instant scheduledFor,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String instanceId,
    String resultSummary,
    String errorMessage,
    String errorDetail) {
}

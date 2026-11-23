package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling;

import java.time.Instant;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.RunStatus;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;

/** A scheduled_job_runs row. {@code parametersOverride} is JSON text or null. */
public record StoredRun(
    long id,
    long scheduleId,
    String jobKey,
    Long companyId,
    TriggerType trigger,
    Long requestedBy,
    String parametersOverride,
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

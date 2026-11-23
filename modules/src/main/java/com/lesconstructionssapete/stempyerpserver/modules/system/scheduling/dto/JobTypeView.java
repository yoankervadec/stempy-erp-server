package com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.dto;

import com.lesconstructionssapete.stempyerpserver.platform.scheduler.DefaultSchedule;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobType;

/**
 * A job type schedules can use.
 *
 * @param permission     needed (on top of scheduler.*) to schedule or run it; null: none
 * @param timeoutSeconds the job's own default; null: the server's
 */
public record JobTypeView(String key, String description, JobType.Scope scope, String permission,
    Long timeoutSeconds, DefaultSchedule defaultSchedule) {
}

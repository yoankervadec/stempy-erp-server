package com.lesconstructionssapete.stempyerpserver.platform.scheduler;

import java.time.Instant;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/** What a job knows about the run it is executing. One per run; not shared between threads. */
public interface JobContext {

  long runId();

  long scheduleId();

  String scheduleName();

  String jobKey();

  TriggerType trigger();

  /** 1 for a regular fire or a manual run, 2+ for retries of the same fire. */
  int attempt();

  /** The fire time this run is for (the request time for a manual run). */
  Instant scheduledFor();

  /** The company the schedule belongs to; empty for an install-wide system job. */
  OptionalLong companyId();

  /** The user who started a MANUAL run; empty otherwise. */
  OptionalLong requestedBy();

  /**
   * Who the run acts as, for orchestrators: the requesting user (with their
   * permissions in the company) for a manual run, else the SYSTEM user with
   * every permission. companyId() is 0 for a system job.
   */
  RequestContext requestContext();

  /** The schedule's parameters, or a manual run's override; an empty object when none. */
  JsonNode parameters();

  /** True once the run should stop (timeout, shutdown, lease lost). */
  boolean isCancelled();

  /** Throws JobCancelledException if {@link #isCancelled()}. Call it between units of work. */
  void throwIfCancelled();

  /** A short outcome to keep with the run, e.g. "42 sessions deleted". Cut to 1000 characters. */
  void setResultSummary(String summary);
}

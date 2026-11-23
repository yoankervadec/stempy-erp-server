package com.lesconstructionssapete.stempyerpserver.platform.scheduler.internal;

import java.time.Instant;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobCancelledException;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.JobContext;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.TriggerType;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/** The JobContext handed to a job: a view of its Claim plus cancellation. */
final class RunContext implements JobContext {

  private final Claim claim;
  private final RequestContext requestContext;
  private final JsonNode parameters;
  private final ActiveRun active;
  private volatile String resultSummary;

  RunContext(Claim claim, RequestContext requestContext, JsonNode parameters, ActiveRun active) {
    this.claim = claim;
    this.requestContext = requestContext;
    this.parameters = parameters;
    this.active = active;
  }

  @Override
  public long runId() {
    return claim.runId();
  }

  @Override
  public long scheduleId() {
    return claim.schedule().id();
  }

  @Override
  public String scheduleName() {
    return claim.schedule().name();
  }

  @Override
  public String jobKey() {
    return claim.schedule().jobKey();
  }

  @Override
  public TriggerType trigger() {
    return claim.trigger();
  }

  @Override
  public int attempt() {
    return claim.attempt();
  }

  @Override
  public Instant scheduledFor() {
    return claim.scheduledFor();
  }

  @Override
  public OptionalLong companyId() {
    Long id = claim.schedule().companyId();
    return id == null ? OptionalLong.empty() : OptionalLong.of(id);
  }

  @Override
  public OptionalLong requestedBy() {
    Long id = claim.requestedBy();
    return id == null ? OptionalLong.empty() : OptionalLong.of(id);
  }

  @Override
  public RequestContext requestContext() {
    return requestContext;
  }

  @Override
  public JsonNode parameters() {
    return parameters;
  }

  @Override
  public boolean isCancelled() {
    return active.isCancelled();
  }

  @Override
  public void throwIfCancelled() {
    ActiveRun.Reason reason = active.reason();
    if (reason != null) {
      throw new JobCancelledException("Run " + claim.runId() + " " + reason.message);
    }
  }

  @Override
  public void setResultSummary(String summary) {
    this.resultSummary = summary;
  }

  String resultSummary() {
    return resultSummary;
  }
}

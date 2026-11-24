package com.lesconstructionssapete.stempyerpserver.platform.events.outbox;

import java.time.Instant;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * What an AsyncEventHandler knows about the message it is handling.
 *
 * @param messageId       outbox.id: stable across attempts, use it as an
 *                        idempotency key (e.g. a webhook header)
 * @param subscriptionKey the subscription the message is for
 * @param attempt         1 for the first attempt
 * @param maxAttempts     after this attempt fails, the message is DEAD
 * @param companyId       the event's company
 * @param enqueuedAt      when the publishing transaction wrote it
 * @param requestContext  who to act as: SYSTEM in the event's company, with
 *                        every declared permission (like a scheduled job)
 */
public record OutboxDelivery(long messageId, String subscriptionKey, int attempt, int maxAttempts, long companyId,
    Instant enqueuedAt, RequestContext requestContext) {

  public OutboxDelivery {
    Objects.requireNonNull(subscriptionKey, "subscriptionKey");
    Objects.requireNonNull(enqueuedAt, "enqueuedAt");
    Objects.requireNonNull(requestContext, "requestContext");
  }

  /** True on the last attempt: a handler may want to log or alert before giving up. */
  public boolean isLastAttempt() {
    return attempt >= maxAttempts;
  }
}

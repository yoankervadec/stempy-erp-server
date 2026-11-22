package com.lesconstructionssapete.stempyerpserver.platform.write;

import java.time.Instant;
import java.util.Objects;

/**
 * Who writes, for which company, and when: fills company_id and the audit
 * columns. {@code now} comes from the server's clock (whole seconds), never
 * NOW() in SQL.
 */
public record WriteContext(long companyId, long userId, Instant now) {

  public WriteContext {
    Objects.requireNonNull(now, "now");
  }
}

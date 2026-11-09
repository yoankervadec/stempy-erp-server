package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Chooses the request id. A caller (API gateway, frontend, another service) may
 * send its own X-Request-Id so one id follows a request across systems, but the
 * value ends up in logs and in a response header, so only a conservative
 * character set is accepted. Anything else is replaced.
 */
public final class RequestIds {

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private RequestIds() {
  }

  public static String resolve(String incoming) {
    if (incoming != null && SAFE.matcher(incoming).matches()) {
      return incoming;
    }
    return UUID.randomUUID().toString();
  }
}

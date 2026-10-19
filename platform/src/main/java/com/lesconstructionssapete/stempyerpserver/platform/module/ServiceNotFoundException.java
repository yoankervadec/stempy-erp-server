package com.lesconstructionssapete.stempyerpserver.platform.module;

import java.util.Set;

/**
 * A module asked for a service nobody registered. This is a wiring bug found at
 * startup,
 * not a request-time error, so it is deliberately not mapped to an HTTP
 * response:
 * let it stop the application with a clear message.
 */
public class ServiceNotFoundException extends IllegalStateException {

  public ServiceNotFoundException(Class<?> type, Set<String> registered) {
    super("No service registered for " + type.getName()
        + ". Registered: " + registered
        + ". If this is another module's API, check that its module is listed BEFORE this one in Main.");
  }
}

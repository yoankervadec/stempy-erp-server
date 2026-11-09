package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Objects;

/**
 * Plain settings, built by the app module from its properties (same idea as
 * DatabaseConfig).
 */
public record WebServerConfig(String host, int port) {

  public WebServerConfig {
    Objects.requireNonNull(host, "host");
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be 0-65535, got " + port);
    }
  }

  /** Listens on all interfaces. */
  public static WebServerConfig ofPort(int port) {
    return new WebServerConfig("0.0.0.0", port);
  }
}
